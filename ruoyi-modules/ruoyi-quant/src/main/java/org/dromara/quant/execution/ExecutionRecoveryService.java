package org.dromara.quant.execution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.dromara.quant.config.QuantProperties;
import org.dromara.quant.credential.domain.QuantCredential;
import org.dromara.quant.credential.mapper.QuantCredentialMapper;
import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.execution.domain.QuantExecutionRecord;
import org.dromara.quant.execution.mapper.QuantExecutionRecordMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 恢复 PENDING/UNKNOWN/ACCEPTED 执行记录，避免“交易所已接受、应用未落库”后再次下单。
 */
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

    /**
     * 创建 ExecutionRecoveryService 实例并初始化其依赖。
     */
    public ExecutionRecoveryService(QuantExecutionRecordMapper recordMapper, QuantCredentialMapper credentialMapper, ExchangeGateway gateway,
                                    ExecutionRecordService recordService, OrderLedgerService orderLedgerService, QuantProperties properties) {
        this.recordMapper = recordMapper;
        this.credentialMapper = credentialMapper;
        this.gateway = gateway;
        this.recordService = recordService;
        this.orderLedgerService = orderLedgerService;
        this.properties = properties;
    }

    /**
     * 注入订单意图恢复器，处理进程重启后的策略派发记录。
     */
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
        List<QuantExecutionRecord> pending = recordMapper.selectList(new LambdaQueryWrapper<QuantExecutionRecord>().in(QuantExecutionRecord::getExecutionStatus, "PENDING",
                "UNKNOWN", "CONFIRMED")
            // Avoid querying the same unresolved order every scheduler tick, while never expiring it.
            .and(q -> q.isNull(QuantExecutionRecord::getLastRecoveryAt).or().le(QuantExecutionRecord::getLastRecoveryAt, LocalDateTime.now().minusMinutes(1)))
            // Do not drop unresolved orders after 24h; oldest never-retried records are examined first.
            .orderByAsc(QuantExecutionRecord::getLastRecoveryAt).orderByAsc(QuantExecutionRecord::getCreatedAt).last("LIMIT 100"));
        for (QuantExecutionRecord record : pending) {
            if (record.getCredentialId() == null || record.getSymbol() == null) {
                continue;
            }
            QuantCredential credential = credentialMapper.selectById(record.getCredentialId());
            if (credential == null || "PAPER".equalsIgnoreCase(credential.getRunMode())) {
                continue;
            }
            try {
                recordService.markRecoveryAttempt(record);
                ExchangeGateway.Credential c = new ExchangeGateway.Credential(credential.getApiKey(), credential.getSecretKey(), credential.getSignatureType(),
                    credential.getRunMode());
                ExchangeGateway.OrderSnapshot exact = null;
                try {
                    exact = gateway.queryOrder(c, record.getSymbol(), record.getExchangeOrderId(), record.getClientOrderId());
                } catch (RuntimeException ignored) {
                    // 精确订单不存在或交易所暂时不可用时，继续使用当前挂单兜底查询。
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
            } catch (RuntimeException ignored) {
                // 网络/交易所异常不能改写交易事实；记录继续保留在当前状态，下一轮继续查询。
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
        List<QuantExecutionRecord> stranded = recordMapper.strandedRecoveredDispatches();
        if (stranded == null) {
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
