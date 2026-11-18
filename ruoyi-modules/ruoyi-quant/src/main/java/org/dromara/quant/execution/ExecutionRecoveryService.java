package org.dromara.quant.execution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.quant.config.QuantProperties;
import org.dromara.quant.credential.domain.QuantCredential;
import org.dromara.quant.credential.mapper.QuantCredentialMapper;
import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.exchange.ExchangeHistoryCollector;
import org.dromara.quant.execution.domain.QuantExecutionRecord;
import org.dromara.quant.execution.mapper.QuantExecutionRecordMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 恢复 PENDING/UNKNOWN/ACCEPTED 执行记录，避免“交易所已接受、应用未落库”后再次下单。
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class ExecutionRecoveryService {
    /**
     * 记录 Mapper。
     */
    private final QuantExecutionRecordMapper recordMapper;
    /**
     * 交易凭证 Mapper。
     */
    private final QuantCredentialMapper credentialMapper;
    /**
     * 交易所统一网关。
     */
    private final ExchangeGateway gateway;
    /**
     * 记录服务。
     */
    private final ExecutionRecordService recordService;
    /**
     * 订单账本服务。
     */
    private final OrderLedgerService orderLedgerService;
    /**
     * 量化模块配置。
     */
    private final QuantProperties properties;
    private volatile LiveOrderDispatcher liveOrderDispatcher;
    private volatile StrategyOrderCrashRecoveryService crashRecovery;

    @Autowired
    public void setCrashRecovery(StrategyOrderCrashRecoveryService recovery) {
        this.crashRecovery = java.util.Objects.requireNonNull(recovery);
    }

    /**
     * 注入 LIVE 派发器以恢复账户和交易对的串行队列状态。
     */
    @Autowired
    public void setLiveOrderDispatcher(LiveOrderDispatcher dispatcher) {
        this.liveOrderDispatcher = java.util.Objects.requireNonNull(dispatcher);
    }

    /**
     * 扫描并恢复未确认的执行记录。
     */
    @Scheduled(fixedDelayString = "#{@quantProperties.runtime.recoverySeconds * 1000}", initialDelay = 20000)
    public void recover() {
        if (!properties.getReconcile().isEnabled()) {
            return;
        }
        final List<QuantExecutionRecord> pending;
        try {
            pending = recordMapper.selectList(new LambdaQueryWrapper<QuantExecutionRecord>().in(QuantExecutionRecord::getExecutionStatus, "PENDING",
                    "UNKNOWN", "CONFIRMED")
                // Avoid querying the same unresolved order every scheduler tick, while never expiring it.
                .and(q -> q.isNull(QuantExecutionRecord::getLastRecoveryAt).or().le(QuantExecutionRecord::getLastRecoveryAt, LocalDateTime.now().minusMinutes(1)))
                // Do not drop unresolved orders after 24h; oldest never-retried records are examined first.
                .orderByAsc(QuantExecutionRecord::getLastRecoveryAt).orderByAsc(QuantExecutionRecord::getCreatedAt).last("LIMIT 100"));
        } catch (RuntimeException ex) {
            log.error("扫描待恢复执行记录失败，本轮不释放任何 LIVE 调度票据", ex);
            return;
        }
        if (pending == null) {
            // A null result is not evidence that no unresolved executions exist.
            // Fail closed instead of running any recovery path that could release a guard.
            log.error("待恢复执行记录扫描返回 null，本轮不释放任何 LIVE 调度票据");
            return;
        }
        if (pending.isEmpty()) {
            recoverStrandedFinalizedDispatches();
            return;
        }
        for (QuantExecutionRecord record : pending) {
            if (record.getCredentialId() == null || record.getSymbol() == null) {
                continue;
            }
            try {
                QuantCredential credential = credentialMapper.selectById(record.getCredentialId());
                if (credential == null || "PAPER".equalsIgnoreCase(credential.getRunMode())) {
                    continue;
                }
                recordService.markRecoveryAttempt(record);
                ExchangeGateway.Credential c = new ExchangeGateway.Credential(credential.getApiKey(), credential.getSecretKey(), credential.getSignatureType(),
                    credential.getRunMode());
                ExchangeGateway.OrderSnapshot exact = null;
                boolean exactLookupFailed = false;
                try {
                    exact = gateway.queryOrder(c, record.getSymbol(), record.getExchangeOrderId(), record.getClientOrderId());
                } catch (RuntimeException lookupFailure) {
                    exactLookupFailed = true;
                    log.warn("Binance 精确订单恢复查询失败，继续使用只读兜底查询 executionId={} credentialId={} symbol={} errorType={}",
                        record.getId(), record.getCredentialId(), record.getSymbol(), lookupFailure.getClass().getSimpleName());
                }
                // 精确订单接口明确返回不存在时，按执行记录创建时间补查历史订单。
                // 每 5 次恢复尝试查询一次，避免大量 UNKNOWN 记录持续放大 REST 请求量。
                if (exact == null && !exactLookupFailed && record.getCreatedAt() != null
                    && record.getRecoveryAttempts() != null && record.getRecoveryAttempts() % 5 == 0
                    && (record.getClientOrderId() != null || record.getExchangeOrderId() != null)) {
                    Instant createdAt = record.getCreatedAt().toInstant(ZoneOffset.UTC);
                    Instant historyFrom = createdAt.minus(Duration.ofMinutes(1));
                    Instant historyTo = createdAt.plus(Duration.ofMinutes(15));
                    Instant exchangeNow = Instant.now().plusMillis(gateway.exchangeTimeOffsetMillis(credential.getRunMode()));
                    if (historyTo.isAfter(exchangeNow)) {
                        historyTo = exchangeNow;
                    }
                    if (!historyFrom.isAfter(historyTo)) {
                        List<ExchangeGateway.OrderSnapshot> history = List.of();
                        boolean historyReadSucceeded = false;
                        try {
                            history = ExchangeHistoryCollector.collect(historyFrom, historyTo, range ->
                                gateway.queryOrderHistory(c, record.getSymbol(), range, 1000L));
                            historyReadSucceeded = true;
                        } catch (RuntimeException historyFailure) {
                            log.warn("Binance 历史订单恢复查询失败，继续保持未确认状态 executionId={} credentialId={} symbol={} errorType={}",
                                record.getId(), record.getCredentialId(), record.getSymbol(), historyFailure.getClass().getSimpleName());
                        }
                        if (historyReadSucceeded) {
                            List<ExchangeGateway.OrderSnapshot> matches = history.stream().filter(order ->
                                order != null && order.orderId() != null && order.orderId() > 0
                                    && order.symbol() != null && record.getSymbol().equalsIgnoreCase(order.symbol())
                                    && order.status() != null && !order.status().isBlank()
                                    && (record.getClientOrderId() == null || record.getClientOrderId().equals(order.clientOrderId()))
                                    && (record.getExchangeOrderId() == null || record.getExchangeOrderId().equals(order.orderId()))
                            ).toList();
                            if (matches.size() > 1) {
                                throw new IllegalStateException("Binance 历史订单恢复返回多个匹配订单，保持执行恢复阻断");
                            }
                            if (matches.size() == 1) {
                                exact = matches.getFirst();
                                log.info("已从 Binance 历史订单找到待恢复执行事实 executionId={} credentialId={} symbol={} orderId={} status={}",
                                    record.getId(), record.getCredentialId(), record.getSymbol(), exact.orderId(), exact.status());
                            }
                        }
                    }
                }
                if (exact != null) {
                    if (exact.symbol() == null || !record.getSymbol().equalsIgnoreCase(exact.symbol()) || record.getExchangeOrderId() != null && !record.getExchangeOrderId().equals(exact.orderId()) || record.getClientOrderId() != null && !record.getClientOrderId().equals(exact.clientOrderId())) {
                        // Query by orderId or clientOrderId must not accept the other
                        // identity changing unexpectedly. Keep UNKNOWN, block dispatch.
                        throw new IllegalStateException("Binance recovery returned conflicting order identity");
                    }
                    if ("CANCEL".equalsIgnoreCase(record.getOperationType()) && ("CANCELED".equalsIgnoreCase(exact.status()) || "EXPIRED".equalsIgnoreCase(exact.status()) ||
                        "FILLED".equalsIgnoreCase(exact.status()) || "REJECTED".equalsIgnoreCase(exact.status())) && (exact.executedQuantity() == null || exact.executedQuantity().signum() < 0 || exact.originalQuantity() == null || exact.originalQuantity().signum() <= 0 || exact.executedQuantity().compareTo(exact.originalQuantity()) > 0)) {
                        throw new IllegalStateException("取消恢复缺少可信的累计成交数量，不得解除调度占用");
                    }
                    syncExchangeWithProvenance(credential, record, exact);
                    if ("CANCEL".equalsIgnoreCase(record.getOperationType())) {
                        String status = exact.status();
                        if ("CANCELED".equalsIgnoreCase(status) || "EXPIRED".equalsIgnoreCase(status) || "REJECTED".equalsIgnoreCase(status)) {
                            recordService.markRecovered(record, exact.orderId(), exact.status());
                        } else if ("FILLED".equalsIgnoreCase(status)) {
                            recordService.markRecovered(record, exact.orderId(), exact.status());
                            // The annotation must be guarded by the exact recovered order and status.
                            // A stale recovery worker must never overwrite a newer execution fact.
                            if (record.getId() != null && exact.orderId() != null) {
                                recordMapper.annotateRecoveredCancelFill(record.getId(), exact.orderId(), status, "取消请求到达时订单已经成交: " + status, LocalDateTime.now());
                            }
                        } else if ("PARTIALLY_FILLED".equalsIgnoreCase(status)) {
                            // PARTIALLY_FILLED can still have live remaining quantity.
                            // Keep CANCEL unresolved and its dispatch guard occupied;
                            // a later exact terminal exchange fact may resolve it.
                            recordService.markUnknown(record, "撤单时订单部分成交，剩余量状态未终结，必须继续对账");
                        }
                    } else {
                        recordService.markRecovered(record, exact.orderId(), exact.status());
                    }
                    releaseRecoveredTicket(credential, record, exact);
                    continue;
                }
                ExchangeGateway.OrderSnapshot matched = record.getClientOrderId() == null ? null :
                    gateway.queryOrders(c, record.getSymbol()).stream().filter(o -> record.getClientOrderId().equals(o.clientOrderId()) && o.symbol() != null && record.getSymbol().equalsIgnoreCase(o.symbol()) && o.orderId() != null && (record.getExchangeOrderId() == null || record.getExchangeOrderId().equals(o.orderId()))).findFirst().orElse(null);
                if (matched != null && !"CANCEL".equalsIgnoreCase(record.getOperationType())) {
                    if ("LIVE".equalsIgnoreCase(credential.getRunMode()) && record.getStrategyInstanceId() != null) {
                        throw new IllegalStateException("LIVE strategy ownership recovery requires an exact Binance query");
                    }
                    // A fallback match must use actual exchange facts, not possibly stale local status.
                    orderLedgerService.syncSnapshot(record.getCredentialId(), matched);
                    recordService.markRecovered(record, matched.orderId(), matched.status());
                    releaseRecoveredTicket(credential, record, matched);
                } else if (record.getCreatedAt() != null && Duration.between(record.getCreatedAt(), LocalDateTime.now()).getSeconds() >= 90) {
                    recordService.markUnknown(record, "超过 90 秒仍无法确认交易所订单状态，保持 UNKNOWN 并等待后续对账");
                }
            } catch (RuntimeException ex) {
                // 网络/交易所异常不能改写交易事实；记录继续保留在当前状态，下一轮继续查询。
                // markRecoveryAttempt throttles repeated attempts, so this warning is bounded per record.
                log.warn("执行记录恢复失败，保留当前状态并等待后续对账 executionId={} credentialId={} symbol={} operation={} status={}",
                    record.getId(), record.getCredentialId(), record.getSymbol(), record.getOperationType(), record.getExecutionStatus(), ex);
            }
        }
        recoverStrandedFinalizedDispatches();
    }

    /**
     * Process death can occur after the execution record becomes RECOVERED but
     * before its dispatch ticket is released. That RECOVERED execution is no
     * longer selected by the ordinary unresolved scan. Recheck Binance's exact
     * identity and persist the fact again before lifting the stranded guard.
     * <p>
     * Never release a CANCEL on NEW/PARTIALLY_FILLED or missing fill totals.
     */
    private void recoverStrandedFinalizedDispatches() {
        if (liveOrderDispatcher == null) {
            return;
        }
        final List<QuantExecutionRecord> stranded;
        try {
            stranded = recordMapper.strandedRecoveredDispatches();
        } catch (RuntimeException ex) {
            // If discovery fails, do not release any possibly stranded dispatch guard.
            log.error("查询已恢复但调度票据未释放的 LIVE 执行记录失败，本轮保持所有保护锁", ex);
            return;
        }
        if (stranded == null || stranded.isEmpty()) {
            return;
        }
        for (QuantExecutionRecord record : stranded) {
            if (record.getCredentialId() == null || record.getSymbol() == null || record.getIdempotencyKey() == null || record.getClientOrderId() == null || record.getExchangeOrderId() == null) {
                continue;
            }
            try {
                QuantCredential credential = credentialMapper.selectById(record.getCredentialId());
                if (credential == null || !"LIVE".equalsIgnoreCase(credential.getRunMode())) {
                    continue;
                }
                var c = new ExchangeGateway.Credential(credential.getApiKey(), credential.getSecretKey(), credential.getSignatureType(), credential.getRunMode());
                var exact = gateway.queryOrder(c, record.getSymbol(), record.getExchangeOrderId(), record.getClientOrderId());
                if (exact == null || exact.orderId() == null || !record.getExchangeOrderId().equals(exact.orderId()) || !record.getClientOrderId().equals(exact.clientOrderId()) || exact.symbol() == null || !record.getSymbol().equalsIgnoreCase(exact.symbol())) {
                    continue;
                }
                if ("CANCEL".equalsIgnoreCase(record.getOperationType()) && !UnifiedTradeExecutionService.verifiedTerminalCancel(exact, record.getSymbol(),
                    record.getExchangeOrderId(), record.getClientOrderId())) {
                    continue;
                }
                var persisted = syncExchangeWithProvenance(credential, record, exact);
                if (persisted == null || persisted.getId() == null) {
                    continue;
                }
                releaseRecoveredTicket(credential, record, exact);
            } catch (RuntimeException ex) {
                // Keep the guard held on any uncertain DB/network result. Other
                // records may still be independently recoverable on this tick.
                log.warn("滞留 LIVE 调度票据恢复失败，继续阻断该账户交易对 credentialId={} executionId={} symbol={} clientOrderId={}",
                    record.getCredentialId(), record.getId(), record.getSymbol(), record.getClientOrderId(), ex);
            }
        }
    }

    /**
     * Exact order fact and immutable pre-send provenance must both commit
     * before an interrupted LIVE strategy ticket can be released.
     */
    private org.dromara.quant.order.domain.QuantOrder syncExchangeWithProvenance(QuantCredential credential, QuantExecutionRecord record, ExchangeGateway.OrderSnapshot snapshot) {
        if ("LIVE".equalsIgnoreCase(credential.getRunMode()) && record.getStrategyInstanceId() != null && record.getStrategyId() != null) {
            if (crashRecovery == null) {
                throw new IllegalStateException("Missing LIVE strategy crash recovery journal");
            }
            return crashRecovery.restore(record, snapshot);
        }
        return orderLedgerService.syncSnapshot(record.getCredentialId(), snapshot);
    }

    /**
     * The exact Binance client order ID, recovered execution state and persisted
     * order ledger must all agree before releasing the durable LIVE dispatch slot.
     */
    private void releaseRecoveredTicket(QuantCredential credential, QuantExecutionRecord record, ExchangeGateway.OrderSnapshot snapshot) {
        if (!"LIVE".equalsIgnoreCase(credential.getRunMode()) || liveOrderDispatcher == null || !"RECOVERED".equals(record.getExecutionStatus()) || record.getClientOrderId() == null || !record.getClientOrderId().equals(snapshot.clientOrderId()) || snapshot.orderId() == null || snapshot.symbol() == null || !record.getSymbol().equalsIgnoreCase(snapshot.symbol()) || "CANCEL".equalsIgnoreCase(record.getOperationType()) && (snapshot.originalQuantity() == null || snapshot.originalQuantity().signum() <= 0 || snapshot.executedQuantity() == null || snapshot.executedQuantity().signum() < 0 || snapshot.executedQuantity().compareTo(snapshot.originalQuantity()) > 0) || "CANCEL".equalsIgnoreCase(record.getOperationType()) && !java.util.Set.of("CANCELED", "EXPIRED", "FILLED", "REJECTED").contains(snapshot.status() == null ? "" : snapshot.status().toUpperCase(java.util.Locale.ROOT))) {
            return;
        }
        liveOrderDispatcher.recoveredFromExchange(record.getCredentialId(), record.getSymbol(), record.getIdempotencyKey(), record.getClientOrderId(), snapshot.orderId());
    }

}
