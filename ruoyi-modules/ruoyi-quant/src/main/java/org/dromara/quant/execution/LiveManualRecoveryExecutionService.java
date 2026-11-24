package org.dromara.quant.execution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.dromara.quant.credential.mapper.QuantCredentialMapper;
import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.execution.domain.QuantExecutionRecord;
import org.dromara.quant.execution.mapper.LiveOrderDispatchMapper;
import org.dromara.quant.execution.mapper.LiveRecoveryApprovalMapper;
import org.dromara.quant.execution.mapper.LiveRecoveryAuditMapper;
import org.dromara.quant.execution.mapper.QuantExecutionRecordMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Fact-verified two-person manual OMS recovery.
 * Only normal OPEN/CLOSE/CANCEL orders with an exact Binance identity qualify.
 * A missing/ambiguous remote order, partial cancellation or stale approval
 * retains the account-symbol dispatch guard. No force-unlock path exists.
 */
@Service
public class LiveManualRecoveryExecutionService {
    private static final Set<String> ORDER_STATES = Set.of("NEW", "PARTIALLY_FILLED", "FILLED", "CANCELED", "EXPIRED", "REJECTED");
    private final LiveRecoveryApprovalMapper cases;
    private final LiveRecoveryAuditMapper audit;
    private final LiveOrderDispatchMapper dispatch;
    private final QuantExecutionRecordMapper executions;
    private final QuantCredentialMapper credentials;
    private final ExchangeGateway gateway;
    private final OrderLedgerService ledger;
    private final ExecutionRecordService records;
    private final TransactionTemplate transaction;

    public LiveManualRecoveryExecutionService(LiveRecoveryApprovalMapper cases, LiveRecoveryAuditMapper audit, LiveOrderDispatchMapper dispatch,
                                              QuantExecutionRecordMapper executions, QuantCredentialMapper credentials, ExchangeGateway gateway, OrderLedgerService ledger,
                                              ExecutionRecordService records, PlatformTransactionManager manager) {
        this.cases = Objects.requireNonNull(cases);
        this.audit = Objects.requireNonNull(audit);
        this.dispatch = Objects.requireNonNull(dispatch);
        this.executions = Objects.requireNonNull(executions);
        this.credentials = Objects.requireNonNull(credentials);
        this.gateway = Objects.requireNonNull(gateway);
        this.ledger = Objects.requireNonNull(ledger);
        this.records = Objects.requireNonNull(records);
        this.transaction = new TransactionTemplate(Objects.requireNonNull(manager));
        this.transaction.setTimeout(8);
    }

    private static boolean validHash(String value) {
        return value != null && value.matches("[0-9a-fA-F]{64}");
    }

    private static String required(Map<String, Object> row, String field) {
        Object value = row.get(field);
        if (!(value instanceof String s) || s.isBlank()) {
            throw new IllegalStateException("恢复案例缺少字段：" + field);
        }
        return (String) value;
    }

    private static long number(Map<String, Object> row, String field) {
        Object value = row.get(field);
        if (!(value instanceof Number n) || n.longValue() <= 0) {
            throw new IllegalStateException("恢复案例缺少字段：valid " + field);
        }
        return n.longValue();
    }

    /**
     * 重新核验工单和证据后执行恢复动作，不重试状态未知的订单。
     */
    public void execute(long caseId, long actorId, String evidenceHash, String detailHash) {
        if (caseId <= 0 || actorId <= 0 || !validHash(evidenceHash) || !validHash(detailHash)) {
            throw new IllegalArgumentException("恢复操作必须提供有效的案例、操作者和证据哈希");
        }
        Map<String, Object> c = cases.findCase(caseId);
        if (c == null || !"APPROVED".equals(c.get("state")) || !evidenceHash.equalsIgnoreCase(String.valueOf(c.get("evidence_sha256")))) {
            throw new IllegalStateException("恢复案例缺少匹配的独立审批");
        }
        long requester = number(c, "requested_by");
        long reviewer = number(c, "reviewed_by");
        if (actorId == requester || actorId == reviewer || requester == reviewer) {
            throw new IllegalStateException("恢复执行人必须与申请人和审核人不同");
        }
        long credentialId = number(c, "credential_id");
        String symbol = required(c, "symbol");
        String key = required(c, "dispatch_key");
        String operation = required(c, "operation_type");
        String clientId = required(c, "expected_client_order_id");
        if (!"TICKETED".equals(c.get("recovery_mode"))) {
            throw new IllegalStateException("历史无票据工单不能进入普通 LIVE 订单恢复流程");
        }
        if (!Set.of("OPEN", "CLOSE", "CANCEL").contains(operation)) {
            throw new IllegalStateException("配置单和保护单必须使用独立的处理流程");
        }
        Long expectedOrderId = c.get("expected_exchange_order_id") == null ? null : number(c, "expected_exchange_order_id");

        var credential = credentials.selectById(credentialId);
        if (credential == null || !"LIVE".equalsIgnoreCase(credential.getRunMode())) {
            throw new IllegalStateException("仅允许恢复经过授权的 LIVE 账户");
        }
        var exchangeCredential = new ExchangeGateway.Credential(credential.getApiKey(), credential.getSecretKey(), credential.getSignatureType(), credential.getRunMode());
        // Exact query only: no queryOrders fallback and no assumption that NOT_FOUND means NOT_SENT.
        var exact = gateway.queryOrder(exchangeCredential, symbol, expectedOrderId, clientId);
        if (exact == null || exact.orderId() == null || exact.orderId() <= 0 || !symbol.equalsIgnoreCase(exact.symbol()) || !clientId.equals(exact.clientOrderId()) || expectedOrderId != null && !expectedOrderId.equals(exact.orderId()) || exact.status() == null || !ORDER_STATES.contains(exact.status().toUpperCase(Locale.ROOT))) {
            throw new IllegalStateException("缺少精确的 Binance 订单事实或事实存在冲突；继续阻断发送");
        }
        if (exact.originalQuantity() == null || exact.originalQuantity().signum() <= 0 || exact.executedQuantity() == null || exact.executedQuantity().signum() < 0 || exact.executedQuantity().compareTo(exact.originalQuantity()) > 0) {
            throw new IllegalStateException("交易所数量信息不可信；继续阻断发送");
        }
        if ("CANCEL".equals(operation) && !UnifiedTradeExecutionService.verifiedTerminalCancel(exact, symbol, expectedOrderId, clientId)) {
            throw new IllegalStateException("撤单尚未进入终态，继续阻断发送");
        }

        // Persist local exact order fact before attempting CAS unlock.
        // A crash here is safe: the ticket is still blocked and recovery can be retried.
        var persisted = ledger.syncSnapshot(credentialId, exact);
        if (persisted == null || persisted.getId() == null) {
            throw new IllegalStateException("订单账本尚未持久化，继续阻断发送");
        }
        transaction.executeWithoutResult(status -> {
            QuantExecutionRecord record =
                executions.selectOne(new LambdaQueryWrapper<QuantExecutionRecord>().eq(QuantExecutionRecord::getCredentialId, credentialId).eq(QuantExecutionRecord::getSymbol,
                    symbol).eq(QuantExecutionRecord::getIdempotencyKey, key));
            if (record == null || !operation.equals(record.getOperationType()) || !clientId.equals(record.getClientOrderId()) || record.getExchangeOrderId() != null && !record.getExchangeOrderId().equals(exact.orderId())) {
                throw new IllegalStateException("本地执行标识不匹配");
            }
            if (!"RECOVERED".equals(record.getExecutionStatus())) {
                records.markRecovered(record, exact.orderId(), exact.status());
                if (!"RECOVERED".equals(record.getExecutionStatus())) {
                    throw new IllegalStateException("执行事实无法持久化");
                }
            }
            String hash = evidenceHash.toLowerCase(Locale.ROOT);
            if (cases.markExecuted(caseId, hash) != 1 || dispatch.recoverVerified(credentialId, symbol.toUpperCase(Locale.ROOT), key, clientId, exact.orderId()) != 1 || dispatch.release(credentialId, symbol.toUpperCase(Locale.ROOT), key) != 1 || audit.append(IdWorker.getId(), caseId, actorId, "EXECUTE", hash, detailHash.toLowerCase(Locale.ROOT)) != 1) {
                throw new IllegalStateException("恢复审计与发送状态的原子 CAS 操作失败，回滚解锁");
            }
        });
    }
}
