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
    private static final Set<String> ORDER_STATES =
        Set.of("NEW", "PARTIALLY_FILLED", "FILLED", "CANCELED", "EXPIRED", "REJECTED");
    private final LiveRecoveryApprovalMapper cases;
    private final LiveRecoveryAuditMapper audit;
    private final LiveOrderDispatchMapper dispatch;
    private final QuantExecutionRecordMapper executions;
    private final QuantCredentialMapper credentials;
    private final ExchangeGateway gateway;
    private final OrderLedgerService ledger;
    private final ExecutionRecordService records;
    private final TransactionTemplate transaction;

    public LiveManualRecoveryExecutionService(LiveRecoveryApprovalMapper cases,
                                              LiveRecoveryAuditMapper audit,
                                              LiveOrderDispatchMapper dispatch,
                                              QuantExecutionRecordMapper executions,
                                              QuantCredentialMapper credentials,
                                              ExchangeGateway gateway,
                                              OrderLedgerService ledger,
                                              ExecutionRecordService records,
                                              PlatformTransactionManager manager) {
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

    public void execute(long caseId, long actorId, String evidenceHash, String detailHash) {
        if (caseId <= 0 || actorId <= 0 || !validHash(evidenceHash) || !validHash(detailHash)) {
            throw new IllegalArgumentException("Recovery requires valid case, actor and evidence hashes");
        }
        Map<String,Object> c = cases.findCase(caseId);
        if (c == null || !"APPROVED".equals(c.get("state"))
            || !evidenceHash.equalsIgnoreCase(String.valueOf(c.get("evidence_sha256")))) {
            throw new IllegalStateException("Recovery case has no matching independent approval");
        }
        long requester = number(c, "requested_by");
        long reviewer = number(c, "reviewed_by");
        if (actorId == requester || actorId == reviewer || requester == reviewer) {
            throw new IllegalStateException("Recovery executor must differ from requester and reviewer");
        }
        long credentialId = number(c, "credential_id");
        String symbol = required(c, "symbol");
        String key = required(c, "dispatch_key");
        String operation = required(c, "operation_type");
        String clientId = required(c, "expected_client_order_id");
        if (!Set.of("OPEN", "CLOSE", "CANCEL").contains(operation)) {
            throw new IllegalStateException("Configuration/protection orders need separate procedures");
        }
        Long expectedOrderId = c.get("expected_exchange_order_id") == null
            ? null : number(c, "expected_exchange_order_id");

        var credential = credentials.selectById(credentialId);
        if (credential == null || !"LIVE".equalsIgnoreCase(credential.getRunMode())) {
            throw new IllegalStateException("Only an authorized LIVE account may be recovered");
        }
        var exchangeCredential = new ExchangeGateway.Credential(
            credential.getApiKey(), credential.getSecretKey(),
            credential.getSignatureType(), credential.getRunMode());
        // Exact query only: no queryOrders fallback and no assumption that NOT_FOUND means NOT_SENT.
        var exact = gateway.queryOrder(exchangeCredential, symbol, expectedOrderId, clientId);
        if (exact == null || exact.orderId() == null || exact.orderId() <= 0
            || !symbol.equalsIgnoreCase(exact.symbol())
            || !clientId.equals(exact.clientOrderId())
            || expectedOrderId != null && !expectedOrderId.equals(exact.orderId())
            || exact.status() == null || !ORDER_STATES.contains(exact.status().toUpperCase(Locale.ROOT))) {
            throw new IllegalStateException("Exact Binance order fact missing or conflicting; keep dispatch blocked");
        }
        if (exact.originalQuantity() == null || exact.originalQuantity().signum() <= 0
            || exact.executedQuantity() == null || exact.executedQuantity().signum() < 0
            || exact.executedQuantity().compareTo(exact.originalQuantity()) > 0) {
            throw new IllegalStateException("Untrustworthy exchange quantities; keep dispatch blocked");
        }
        if ("CANCEL".equals(operation) && !UnifiedTradeExecutionService.verifiedTerminalCancel(
            exact, symbol, expectedOrderId, clientId)) {
            throw new IllegalStateException("Cancel not terminal, dispatch remains blocked");
        }

        // Persist local exact order fact before attempting CAS unlock.
        // A crash here is safe: the ticket is still blocked and recovery can be retried.
        var persisted = ledger.syncSnapshot(credentialId, exact);
        if (persisted == null || persisted.getId() == null) {
            throw new IllegalStateException("Order ledger not durable; keep dispatch blocked");
        }
        transaction.executeWithoutResult(status -> {
            QuantExecutionRecord record = executions.selectOne(
                new LambdaQueryWrapper<QuantExecutionRecord>()
                    .eq(QuantExecutionRecord::getCredentialId, credentialId)
                    .eq(QuantExecutionRecord::getSymbol, symbol)
                    .eq(QuantExecutionRecord::getIdempotencyKey, key));
            if (record == null || !operation.equals(record.getOperationType())
                || !clientId.equals(record.getClientOrderId())
                || record.getExchangeOrderId() != null && !record.getExchangeOrderId().equals(exact.orderId())) {
                throw new IllegalStateException("Local execution identity mismatch");
            }
            if (!"RECOVERED".equals(record.getExecutionStatus())) {
                records.markRecovered(record, exact.orderId(), exact.status());
                if (!"RECOVERED".equals(record.getExecutionStatus())) {
                    throw new IllegalStateException("Execution fact could not be persisted");
                }
            }
            String hash = evidenceHash.toLowerCase(Locale.ROOT);
            if (cases.markExecuted(caseId, hash) != 1
                || dispatch.recoverVerified(credentialId, symbol.toUpperCase(Locale.ROOT),
                    key, clientId, exact.orderId()) != 1
                || dispatch.release(credentialId, symbol.toUpperCase(Locale.ROOT), key) != 1
                || audit.append(IdWorker.getId(), caseId, actorId, "EXECUTE", hash,
                    detailHash.toLowerCase(Locale.ROOT)) != 1) {
                throw new IllegalStateException("Atomic recovery audit/dispatch CAS failed; rollback unlock");
            }
        });
    }

    private static boolean validHash(String value) {
        return value != null && value.matches("[0-9a-fA-F]{64}");
    }
    private static String required(Map<String,Object> row, String field) {
        Object value = row.get(field);
        if (!(value instanceof String s) || s.isBlank()) {
            throw new IllegalStateException("Recovery case lacks " + field);
        }
        return (String)value;
    }
    private static long number(Map<String,Object> row, String field) {
        Object value = row.get(field);
        if (!(value instanceof Number n) || n.longValue() <= 0) {
            throw new IllegalStateException("Recovery case lacks valid " + field);
        }
        return n.longValue();
    }
}
