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
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class ExecutionRecoveryServiceTest {
    @Test
    void scansUnresolvedOrdersWithoutDiscardingThoseOlderThanOneDay() {
        var records = mock(QuantExecutionRecordMapper.class);
        when(records.selectList(any())).thenReturn(List.of());
        var service = new ExecutionRecoveryService(records, mock(QuantCredentialMapper.class), mock(ExchangeGateway.class), mock(ExecutionRecordService.class),
            mock(OrderLedgerService.class), new QuantProperties());
        service.recover();
        verify(records).selectList(argThat(wrapper -> {
            String sql = wrapper.getSqlSegment().toLowerCase(java.util.Locale.ROOT);
            return sql.contains("last_recovery_at") && sql.contains("created_at") && !sql.contains("updated_at") && sql.contains("limit 100");
        }));
    }

    @Test
    void conflictingBinanceOrderIdentityDoesNotRewriteLedgerOrUnlockDispatch() {
        var records = mock(QuantExecutionRecordMapper.class);
        var credentials = mock(QuantCredentialMapper.class);
        var gateway = mock(ExchangeGateway.class);
        var recordService = mock(ExecutionRecordService.class);
        var ledger = mock(OrderLedgerService.class);
        var dispatcher = mock(LiveOrderDispatcher.class);
        var record = new QuantExecutionRecord();
        record.setCredentialId(1L);
        record.setSymbol("BTCUSDT");
        record.setIdempotencyKey("entry-123");
        record.setClientOrderId("correct-client");
        record.setExchangeOrderId(123L);
        record.setExecutionStatus("UNKNOWN");
        record.setOperationType("OPEN");
        when(records.selectList(any())).thenReturn(List.of(record));
        var credential = new QuantCredential();
        credential.setApiKey("test");
        credential.setSecretKey("test");
        credential.setRunMode("LIVE");
        when(credentials.selectById(1L)).thenReturn(credential);
        when(gateway.queryOrder(any(), eq("BTCUSDT"), eq(123L), eq("correct-client"))).thenReturn(new ExchangeGateway.OrderSnapshot("BTCUSDT", 123L, "WRONG-client", "BUY",
            "MARKET", "FILLED", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, Instant.now()));
        var service = new ExecutionRecoveryService(records, credentials, gateway, recordService, ledger, new QuantProperties());
        service.setLiveOrderDispatcher(dispatcher);
        service.recover();
        verifyNoInteractions(ledger);
        verify(recordService, never()).markRecovered(any(), any(), any());
        verifyNoInteractions(dispatcher);
    }

    @Test
    void partiallyFilledCancelKeepsUnknownAndDispatchGuardOccupiedUntilTerminal() {
        var records = mock(QuantExecutionRecordMapper.class);
        var credentials = mock(QuantCredentialMapper.class);
        var gateway = mock(ExchangeGateway.class);
        var recovery = mock(ExecutionRecordService.class);
        var ledger = mock(OrderLedgerService.class);
        var dispatcher = mock(LiveOrderDispatcher.class);

        var record = new QuantExecutionRecord();
        record.setCredentialId(1L);
        record.setIdempotencyKey("cancel-key");
        record.setSymbol("BTCUSDT");
        record.setClientOrderId("original-client");
        record.setExchangeOrderId(99L);
        record.setOperationType("CANCEL");
        record.setExecutionStatus("UNKNOWN");
        when(records.selectList(any())).thenReturn(List.of(record));

        var credential = new QuantCredential();
        credential.setApiKey("test");
        credential.setSecretKey("test");
        credential.setRunMode("LIVE");
        when(credentials.selectById(1L)).thenReturn(credential);
        var partial = new ExchangeGateway.OrderSnapshot("BTCUSDT", 99L, "original-client", "BUY", "LIMIT", "PARTIALLY_FILLED", BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("0" +
            ".4"), Instant.now());
        when(gateway.queryOrder(any(), eq("BTCUSDT"), eq(99L), eq("original-client"))).thenReturn(partial);
        var service = new ExecutionRecoveryService(records, credentials, gateway, recovery, ledger, new QuantProperties());
        service.setLiveOrderDispatcher(dispatcher);
        service.recover();
        verify(ledger).syncSnapshot(1L, partial);
        verify(recovery).markUnknown(eq(record), contains("部分成交"));
        verify(recovery, never()).markRecovered(any(), any(), any());
        verifyNoInteractions(dispatcher);
    }

    @Test
    void cancelMayRecoverAndUnlockWhenExactExchangeFactBecomesFilled() {
        var records = mock(QuantExecutionRecordMapper.class);
        var credentials = mock(QuantCredentialMapper.class);
        var gateway = mock(ExchangeGateway.class);
        var recovery = mock(ExecutionRecordService.class);
        var ledger = mock(OrderLedgerService.class);
        var dispatcher = mock(LiveOrderDispatcher.class);

        var record = new QuantExecutionRecord();
        record.setCredentialId(1L);
        record.setIdempotencyKey("cancel-key");
        record.setSymbol("BTCUSDT");
        record.setClientOrderId("original-client");
        record.setExchangeOrderId(99L);
        record.setOperationType("CANCEL");
        record.setExecutionStatus("UNKNOWN");
        when(records.selectList(any())).thenReturn(List.of(record));
        var credential = new QuantCredential();
        credential.setApiKey("test");
        credential.setSecretKey("test");
        credential.setRunMode("LIVE");
        when(credentials.selectById(1L)).thenReturn(credential);
        var finished = new ExchangeGateway.OrderSnapshot("BTCUSDT", 99L, "original-client", "BUY", "LIMIT", "FILLED", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
            Instant.now());
        when(gateway.queryOrder(any(), eq("BTCUSDT"), eq(99L), eq("original-client"))).thenReturn(finished);
        doAnswer(i -> {
            record.setExecutionStatus("RECOVERED");
            return null;
        }).when(recovery).markRecovered(record, 99L, "FILLED");
        var service = new ExecutionRecoveryService(records, credentials, gateway, recovery, ledger, new QuantProperties());
        service.setLiveOrderDispatcher(dispatcher);
        service.recover();
        verify(recovery).markRecovered(record, 99L, "FILLED");
        verify(dispatcher).recoveredFromExchange(1L, "BTCUSDT", "cancel-key", "original-client", 99L);
    }

    @Test
    void fallbackOrderMatchUsesExchangeStatusRatherThanStaleLocalStatus() {
        var records = mock(QuantExecutionRecordMapper.class);
        var credentials = mock(QuantCredentialMapper.class);
        var gateway = mock(ExchangeGateway.class);
        var recovery = mock(ExecutionRecordService.class);
        var ledger = mock(OrderLedgerService.class);

        var record = new QuantExecutionRecord();
        record.setCredentialId(1L);
        record.setSymbol("BTCUSDT");
        record.setClientOrderId("client-123");
        record.setIdempotencyKey("entry-123");
        record.setOperationType("OPEN");
        record.setExchangeOrderStatus("NEW");
        record.setExecutionStatus("UNKNOWN");
        record.setCreatedAt(LocalDateTime.now().minusDays(3));
        when(records.selectList(any())).thenReturn(List.of(record));

        var credential = new QuantCredential();
        credential.setApiKey("test");
        credential.setSecretKey("test");
        credential.setSignatureType("HMAC");
        credential.setRunMode("LIVE");
        when(credentials.selectById(1L)).thenReturn(credential);

        var exchangeOrder = new ExchangeGateway.OrderSnapshot("BTCUSDT", 123L, "client-123", "BUY", "MARKET", "FILLED", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
            Instant.now());
        when(gateway.queryOrders(any(), eq("BTCUSDT"))).thenReturn(List.of(exchangeOrder));

        var dispatcher = mock(LiveOrderDispatcher.class);
        doAnswer(invocation -> {
            record.setExecutionStatus("RECOVERED");
            return null;
        }).when(recovery).markRecovered(record, 123L, "FILLED");
        var service = new ExecutionRecoveryService(records, credentials, gateway, recovery, ledger, new QuantProperties());
        service.setLiveOrderDispatcher(dispatcher);
        service.recover();

        verify(dispatcher).recoveredFromExchange(1L, "BTCUSDT", "entry-123", "client-123", 123L);
        verify(ledger).syncSnapshot(1L, exchangeOrder);
        verify(recovery).markRecovered(record, 123L, "FILLED");
        verify(gateway, never()).placeOrder(any());
    }
}
