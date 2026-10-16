package org.dromara.quant.execution;

import org.dromara.quant.credential.domain.QuantCredential;
import org.dromara.quant.credential.mapper.QuantCredentialMapper;
import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.execution.domain.QuantExecutionRecord;
import org.dromara.quant.execution.mapper.LiveOrderDispatchMapper;
import org.dromara.quant.execution.mapper.LiveRecoveryApprovalMapper;
import org.dromara.quant.execution.mapper.LiveRecoveryAuditMapper;
import org.dromara.quant.execution.mapper.QuantExecutionRecordMapper;
import org.dromara.quant.order.domain.QuantOrder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class LiveManualRecoveryExecutionServiceTest {
    private final LiveRecoveryApprovalMapper cases = mock(LiveRecoveryApprovalMapper.class);
    private final LiveRecoveryAuditMapper audit = mock(LiveRecoveryAuditMapper.class);
    private final LiveOrderDispatchMapper dispatch = mock(LiveOrderDispatchMapper.class);
    private final QuantExecutionRecordMapper executions = mock(QuantExecutionRecordMapper.class);
    private final QuantCredentialMapper credentials = mock(QuantCredentialMapper.class);
    private final ExchangeGateway gateway = mock(ExchangeGateway.class);
    private final OrderLedgerService ledger = mock(OrderLedgerService.class);
    private final ExecutionRecordService records = mock(ExecutionRecordService.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final LiveManualRecoveryExecutionService service = new LiveManualRecoveryExecutionService(cases, audit, dispatch, executions, credentials, gateway, ledger, records,
        manager);

    private Map<String, Object> approved(String operation) {
        var map = new HashMap<String, Object>();
        map.put("state", "APPROVED");
        map.put("evidence_sha256", "a".repeat(64));
        map.put("requested_by", 10L);
        map.put("reviewed_by", 11L);
        map.put("credential_id", 20L);
        map.put("symbol", "BTCUSDT");
        map.put("dispatch_key", "key-1");
        map.put("operation_type", operation);
        map.put("expected_client_order_id", "client-1");
        return map;
    }

    @Test
    void distinctThirdExecutorRequiredBeforeBinanceCall() {
        when(cases.findCase(1L)).thenReturn(approved("OPEN"));
        assertThrows(IllegalStateException.class, () -> service.execute(1L, 11L, "a".repeat(64), "b".repeat(64)));
        verifyNoInteractions(gateway, dispatch, ledger, audit);
    }

    @Test
    void configurationTicketsCannotUseOrderRecovery() {
        when(cases.findCase(1L)).thenReturn(approved("CONFIG_SETTINGS"));
        assertThrows(IllegalStateException.class, () -> service.execute(1L, 12L, "a".repeat(64), "b".repeat(64)));
        verifyNoInteractions(gateway, dispatch, ledger, audit);
    }

    @Test
    void unknownBinanceOrderNeverReleasesGuard() {
        when(cases.findCase(1L)).thenReturn(approved("OPEN"));
        var credential = new QuantCredential();
        credential.setRunMode("LIVE");
        when(credentials.selectById(20L)).thenReturn(credential);
        assertThrows(IllegalStateException.class, () -> service.execute(1L, 12L, "a".repeat(64), "b".repeat(64)));
        verify(gateway).queryOrder(any(), eq("BTCUSDT"), isNull(), eq("client-1"));
        verifyNoInteractions(dispatch, ledger, audit);
    }

    @Test
    void activePartialFillCannotCompleteCancelRecovery() {
        when(cases.findCase(1L)).thenReturn(approved("CANCEL"));
        var credential = new QuantCredential();
        credential.setRunMode("LIVE");
        when(credentials.selectById(20L)).thenReturn(credential);
        when(gateway.queryOrder(any(), eq("BTCUSDT"), isNull(), eq("client-1"))).thenReturn(new ExchangeGateway.OrderSnapshot("BTCUSDT", 33L, "client-1", "BUY", "LIMIT",
            "PARTIALLY_FILLED", BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ONE, Instant.now()));
        assertThrows(IllegalStateException.class, () -> service.execute(1L, 12L, "a".repeat(64), "b".repeat(64)));
        verifyNoInteractions(dispatch, ledger, audit);
    }

    @Test
    void approvedExactFactAtomicallyRecoversTicketAndAudits() {
        when(cases.findCase(1L)).thenReturn(approved("OPEN"));
        var credential = new QuantCredential();
        credential.setRunMode("LIVE");
        when(credentials.selectById(20L)).thenReturn(credential);
        var snapshot = new ExchangeGateway.OrderSnapshot("BTCUSDT", 33L, "client-1", "BUY", "LIMIT", "FILLED", BigDecimal.ONE, BigDecimal.TEN, BigDecimal.TEN, Instant.now());
        when(gateway.queryOrder(any(), eq("BTCUSDT"), isNull(), eq("client-1"))).thenReturn(snapshot);
        var persisted = new QuantOrder();
        persisted.setId(55L);
        when(ledger.syncSnapshot(20L, snapshot)).thenReturn(persisted);
        var exec = new QuantExecutionRecord();
        exec.setId(100L);
        exec.setCredentialId(20L);
        exec.setSymbol("BTCUSDT");
        exec.setIdempotencyKey("key-1");
        exec.setClientOrderId("client-1");
        exec.setOperationType("OPEN");
        exec.setExecutionStatus("RECOVERED");
        exec.setExchangeOrderId(33L);
        when(executions.selectOne(any())).thenReturn(exec);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(cases.markExecuted(1L, "a".repeat(64))).thenReturn(1);
        when(dispatch.recoverVerified(20L, "BTCUSDT", "key-1", "client-1", 33L)).thenReturn(1);
        when(dispatch.release(20L, "BTCUSDT", "key-1")).thenReturn(1);
        when(audit.append(anyLong(), eq(1L), eq(12L), eq("EXECUTE"), eq("a".repeat(64)), eq("b".repeat(64)))).thenReturn(1);
        service.execute(1L, 12L, "a".repeat(64), "b".repeat(64));
        verify(cases).markExecuted(1L, "a".repeat(64));
        verify(dispatch).release(20L, "BTCUSDT", "key-1");
        verify(audit).append(anyLong(), eq(1L), eq(12L), eq("EXECUTE"), eq("a".repeat(64)), eq("b".repeat(64)));
        verify(manager).commit(any());
    }

    @Test
    void mismatchedExchangeIdentityNeverTouchesLedger() {
        when(cases.findCase(1L)).thenReturn(approved("OPEN"));
        var credential = new QuantCredential();
        credential.setRunMode("LIVE");
        when(credentials.selectById(20L)).thenReturn(credential);
        when(gateway.queryOrder(any(), anyString(), isNull(), anyString())).thenReturn(new ExchangeGateway.OrderSnapshot("BTCUSDT", 33L, "wrong-client", "BUY", "LIMIT", "NEW",
            BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ZERO, Instant.now()));
        assertThrows(IllegalStateException.class, () -> service.execute(1L, 12L, "a".repeat(64), "b".repeat(64)));
        verifyNoInteractions(dispatch, ledger, audit);
    }
}
