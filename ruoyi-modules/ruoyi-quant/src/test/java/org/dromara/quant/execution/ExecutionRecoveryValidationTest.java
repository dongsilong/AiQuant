package org.dromara.quant.execution;

import org.dromara.quant.config.QuantProperties;
import org.dromara.quant.credential.domain.QuantCredential;
import org.dromara.quant.credential.mapper.QuantCredentialMapper;
import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.execution.domain.QuantExecutionRecord;
import org.dromara.quant.execution.mapper.QuantExecutionRecordMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Identity and numeric-fact validation must run before any persistent recovery
 * or release of the LIVE account-symbol dispatch slot.
 */
@Tag("dev")
class ExecutionRecoveryValidationTest {
    private final QuantExecutionRecordMapper records = mock(QuantExecutionRecordMapper.class);
    private final QuantCredentialMapper credentials = mock(QuantCredentialMapper.class);
    private final ExchangeGateway gateway = mock(ExchangeGateway.class);
    private final ExecutionRecordService recovery = mock(ExecutionRecordService.class);
    private final OrderLedgerService ledger = mock(OrderLedgerService.class);
    private final LiveOrderDispatcher dispatcher = mock(LiveOrderDispatcher.class);

    private void recover(ExchangeGateway.OrderSnapshot response, String operation) {
        var item = new QuantExecutionRecord();
        item.setCredentialId(1L);
        item.setSymbol("BTCUSDT");
        item.setExchangeOrderId(99L);
        item.setClientOrderId("original");
        item.setIdempotencyKey("cancel-99");
        item.setOperationType(operation);
        item.setExecutionStatus("UNKNOWN");
        when(records.selectList(any())).thenReturn(List.of(item));
        var credential = new QuantCredential();
        credential.setApiKey("test");
        credential.setSecretKey("test");
        credential.setRunMode("LIVE");
        when(credentials.selectById(1L)).thenReturn(credential);
        when(gateway.queryOrder(any(), eq("BTCUSDT"), eq(99L), eq("original"))).thenReturn(response);
        var service = new ExecutionRecoveryService(records, credentials, gateway, recovery, ledger, new QuantProperties());
        service.setLiveOrderDispatcher(dispatcher);
        service.recover();
    }

    private ExchangeGateway.OrderSnapshot snapshot(String symbol, String status, BigDecimal executed, BigDecimal original) {
        return new ExchangeGateway.OrderSnapshot(symbol, 99L, "original", "BUY", "LIMIT", status, BigDecimal.ONE, original, executed, Instant.now());
    }

    @Test
    void crossSymbolOrderCannotWriteLedgerOrRecoverExecution() {
        recover(snapshot("ETHUSDT", "FILLED", BigDecimal.ONE, BigDecimal.ONE), "OPEN");
        verifyNoInteractions(ledger, dispatcher);
        verify(recovery, never()).markRecovered(any(), any(), any());
    }

    @Test
    void missingExecutedQuantityCannotUnlockCancel() {
        recover(snapshot("BTCUSDT", "CANCELED", null, BigDecimal.ONE), "CANCEL");
        verifyNoInteractions(ledger, dispatcher);
        verify(recovery, never()).markRecovered(any(), any(), any());
    }

    @Test
    void cumulativeExecutedQuantityAboveOriginalCannotUnlockCancel() {
        recover(snapshot("BTCUSDT", "FILLED", new BigDecimal("1.2"), BigDecimal.ONE), "CANCEL");
        verifyNoInteractions(ledger, dispatcher);
        verify(recovery, never()).markRecovered(any(), any(), any());
    }
}
