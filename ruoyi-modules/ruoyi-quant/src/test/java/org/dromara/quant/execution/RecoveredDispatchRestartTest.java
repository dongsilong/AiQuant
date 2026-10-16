package org.dromara.quant.execution;

import org.dromara.quant.config.QuantProperties;
import org.dromara.quant.credential.domain.QuantCredential;
import org.dromara.quant.credential.mapper.QuantCredentialMapper;
import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.execution.domain.QuantExecutionRecord;
import org.dromara.quant.execution.mapper.QuantExecutionRecordMapper;
import org.dromara.quant.order.domain.QuantOrder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@Tag("dev")
class RecoveredDispatchRestartTest {
    private final QuantExecutionRecordMapper mapper = mock(QuantExecutionRecordMapper.class);
    private final QuantCredentialMapper credentials = mock(QuantCredentialMapper.class);
    private final ExchangeGateway gateway = mock(ExchangeGateway.class);
    private final OrderLedgerService ledger = mock(OrderLedgerService.class);
    private final LiveOrderDispatcher dispatcher = mock(LiveOrderDispatcher.class);

    private QuantExecutionRecord recovered(String operation) {
        var x = new QuantExecutionRecord();
        x.setCredentialId(1L);
        x.setSymbol("BTCUSDT");
        x.setIdempotencyKey("original-key");
        x.setClientOrderId("real-cid");
        x.setExchangeOrderId(99L);
        x.setOperationType(operation);
        x.setExecutionStatus("RECOVERED");
        return x;
    }

    private ExchangeGateway.OrderSnapshot snapshot(String status, BigDecimal filled) {
        return new ExchangeGateway.OrderSnapshot("BTCUSDT", 99L, "real-cid", "BUY", "LIMIT", status, BigDecimal.ONE, BigDecimal.ONE, filled, Instant.now());
    }

    private void prepare(QuantExecutionRecord x, ExchangeGateway.OrderSnapshot fact, boolean ledgerPersisted) {
        when(mapper.selectList(any())).thenReturn(List.of());
        when(mapper.strandedRecoveredDispatches()).thenReturn(List.of(x));
        var c = new QuantCredential();
        c.setApiKey("test");
        c.setSecretKey("test");
        c.setRunMode("LIVE");
        when(credentials.selectById(1L)).thenReturn(c);
        when(gateway.queryOrder(any(), eq("BTCUSDT"), eq(99L), eq("real-cid"))).thenReturn(fact);
        if (ledgerPersisted) {
            var local = new QuantOrder();
            local.setId(33L);
            when(ledger.syncSnapshot(1L, fact)).thenReturn(local);
        }
        var recovery = new ExecutionRecoveryService(mapper, credentials, gateway, mock(ExecutionRecordService.class), ledger, new QuantProperties());
        recovery.setLiveOrderDispatcher(dispatcher);
        recovery.recover();
    }

    @Test
    void crashedAfterRecoveredBeforeGuardReleaseCanRecoverWithoutResending() {
        prepare(recovered("OPEN"), snapshot("FILLED", BigDecimal.ONE), true);
        verify(ledger).syncSnapshot(eq(1L), any());
        verify(dispatcher).recoveredFromExchange(1L, "BTCUSDT", "original-key", "real-cid", 99L);
        verify(gateway, never()).placeOrder(any());
        verify(gateway, never()).cancelOrder(any());
    }

    @Test
    void strandedCancelStillPartiallyFilledMustRemainBlocked() {
        prepare(recovered("CANCEL"), snapshot("PARTIALLY_FILLED", new BigDecimal("0.4")), true);
        verifyNoInteractions(ledger, dispatcher);
    }

    @Test
    void failedLocalLedgerRecoveryCannotReleaseGuard() {
        prepare(recovered("CANCEL"), snapshot("CANCELED", new BigDecimal("0.4")), false);
        verify(ledger).syncSnapshot(eq(1L), any());
        verifyNoInteractions(dispatcher);
    }

    @Test
    void missingOrConflictingExchangeIdentityNeverClearsRecoveredGuard() {
        var wrong = new ExchangeGateway.OrderSnapshot("BTCUSDT", 99L, "wrong-cid", "BUY", "LIMIT", "FILLED", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, Instant.now());
        prepare(recovered("OPEN"), wrong, true);
        verifyNoInteractions(ledger, dispatcher);
    }
}
