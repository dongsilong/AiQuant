package org.dromara.quant.execution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.dromara.quant.config.QuantProperties;
import org.dromara.quant.credential.domain.QuantCredential;
import org.dromara.quant.credential.mapper.QuantCredentialMapper;
import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.execution.domain.QuantExecutionRecord;
import org.dromara.quant.execution.mapper.QuantExecutionRecordMapper;
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

    @org.springframework.beans.factory.annotation.Autowired
    public void setLiveOrderDispatcher(LiveOrderDispatcher dispatcher) {
        this.liveOrderDispatcher = java.util.Objects.requireNonNull(dispatcher);
    }

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
            .and(q -> q.isNull(QuantExecutionRecord::getLastRecoveryAt)
                .or().le(QuantExecutionRecord::getLastRecoveryAt, LocalDateTime.now().minusMinutes(1)))
            // Do not drop unresolved orders after 24h; oldest never-retried records are examined first.
            .orderByAsc(QuantExecutionRecord::getLastRecoveryAt)
            .orderByAsc(QuantExecutionRecord::getCreatedAt).last("LIMIT 100"));
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
                    if (record.getExchangeOrderId() != null
                        && !record.getExchangeOrderId().equals(exact.orderId())
                        || record.getClientOrderId() != null
                        && !record.getClientOrderId().equals(exact.clientOrderId())) {
                        // Query by orderId or clientOrderId must not accept the other
                        // identity changing unexpectedly. Keep UNKNOWN, block dispatch.
                        throw new IllegalStateException("Binance recovery returned conflicting order identity");
                    }
                    orderLedgerService.syncSnapshot(record.getCredentialId(), exact);
                    if ("CANCEL".equalsIgnoreCase(record.getOperationType())) {
                        String status = exact.status();
                        if ("CANCELED".equalsIgnoreCase(status) || "EXPIRED".equalsIgnoreCase(status)
                            || "REJECTED".equalsIgnoreCase(status)) {
                            recordService.markRecovered(record, exact.orderId(), exact.status());
                        } else if ("FILLED".equalsIgnoreCase(status)) {
                            recordService.markRecovered(record, exact.orderId(), exact.status());
                            // The annotation must be guarded by the exact recovered order and status.
                            // A stale recovery worker must never overwrite a newer execution fact.
                            if (record.getId() != null && exact.orderId() != null) {
                                recordMapper.annotateRecoveredCancelFill(record.getId(), exact.orderId(), status,
                                    "取消请求到达时订单已经成交: " + status, LocalDateTime.now());
                            }
                        } else if ("PARTIALLY_FILLED".equalsIgnoreCase(status)) {
                            // PARTIALLY_FILLED can still have live remaining quantity.
                            // Keep CANCEL unresolved and its dispatch guard occupied;
                            // a later exact terminal exchange fact may resolve it.
                            recordService.markUnknown(record,
                                "撤单时订单部分成交，剩余量状态未终结，必须继续对账");
                        }
                    } else {
                        recordService.markRecovered(record, exact.orderId(), exact.status());
                    }
                    releaseRecoveredTicket(credential, record, exact);
                    continue;
                }
                ExchangeGateway.OrderSnapshot matched = record.getClientOrderId() == null ? null
                    : gateway.queryOrders(c, record.getSymbol()).stream()
                        .filter(o -> record.getClientOrderId().equals(o.clientOrderId()))
                        .findFirst().orElse(null);
                if (matched != null && !"CANCEL".equalsIgnoreCase(record.getOperationType())) {
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
    }
    /**
     * The exact Binance client order ID, recovered execution state and persisted
     * order ledger must all agree before releasing the durable LIVE dispatch slot.
     */
    private void releaseRecoveredTicket(QuantCredential credential, QuantExecutionRecord record,
                                        ExchangeGateway.OrderSnapshot snapshot) {
        if (!"LIVE".equalsIgnoreCase(credential.getRunMode()) || liveOrderDispatcher == null
            || !"RECOVERED".equals(record.getExecutionStatus())
            || record.getClientOrderId() == null
            || !record.getClientOrderId().equals(snapshot.clientOrderId())
            || snapshot.orderId() == null
            || "CANCEL".equalsIgnoreCase(record.getOperationType())
                && !java.util.Set.of("CANCELED", "EXPIRED", "FILLED", "REJECTED")
                    .contains(snapshot.status() == null ? "" : snapshot.status().toUpperCase(java.util.Locale.ROOT))) {
            return;
        }
        liveOrderDispatcher.recoveredFromExchange(record.getCredentialId(), record.getSymbol(),
            record.getIdempotencyKey(), record.getClientOrderId(), snapshot.orderId());
    }

}
