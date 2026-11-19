package org.dromara.quant.execution;

import org.dromara.quant.config.QuantProperties;
import org.dromara.quant.credential.domain.QuantCredential;
import org.dromara.quant.credential.mapper.QuantCredentialMapper;
import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.execution.domain.QuantExecutionRecord;
import org.dromara.quant.execution.mapper.QuantExecutionRecordMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class ExecutionRecoveryServiceTest {
    @BeforeAll
    static void initializeExecutionRecordLambdaMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), "execution-recovery-test"), QuantExecutionRecord.class);
    }

    @Test
    void scansUnresolvedOrdersWithoutDiscardingThoseOlderThanOneDay() {
        var records = mock(QuantExecutionRecordMapper.class);
        when(records.selectList(any())).thenReturn(List.of());
        var service = new ExecutionRecoveryService(records, mock(QuantCredentialMapper.class), mock(ExchangeGateway.class), mock(ExecutionRecordService.class),
            mock(OrderLedgerService.class), new QuantProperties());
        service.recover();
        var query = org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        verify(records).selectList(query.capture());
        String sql = query.getValue().getCustomSqlSegment().toLowerCase(java.util.Locale.ROOT);
        org.junit.jupiter.api.Assertions.assertTrue(sql.contains("lastrecoveryat"), sql);
        org.junit.jupiter.api.Assertions.assertTrue(sql.contains("createdat"));
        org.junit.jupiter.api.Assertions.assertFalse(sql.contains("createdat <"), "old unresolved executions must not expire");
        org.junit.jupiter.api.Assertions.assertTrue(sql.contains("limit 100"));
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
    @Test
    void recoversUnknownOrderFromHistoricalOrderWhenExactLookupReportsAbsent() {
        var records = mock(QuantExecutionRecordMapper.class);
        var credentials = mock(QuantCredentialMapper.class);
        var gateway = mock(ExchangeGateway.class);
        var recordService = mock(ExecutionRecordService.class);
        var ledger = mock(OrderLedgerService.class);

        var record = new QuantExecutionRecord();
        record.setId(10L);
        record.setCredentialId(1L);
        record.setSymbol("PIPPINUSDT");
        record.setIdempotencyKey("entry-history-fallback");
        record.setClientOrderId("client-history-fallback");
        record.setOperationType("OPEN");
        record.setExecutionStatus("UNKNOWN");
        record.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(2));
        record.setRecoveryAttempts(4);
        when(records.selectList(any())).thenReturn(List.of(record));

        var credential = new QuantCredential();
        credential.setApiKey("test");
        credential.setSecretKey("test");
        credential.setRunMode("TESTNET");
        when(credentials.selectById(1L)).thenReturn(credential);
        var historical = new ExchangeGateway.OrderSnapshot("PIPPINUSDT", 321L, "client-history-fallback", "BUY", "MARKET", "FILLED",
            BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, Instant.now());
        when(gateway.queryOrder(any(), eq("PIPPINUSDT"), isNull(), eq("client-history-fallback"))).thenReturn(null);
        when(gateway.queryOrderHistory(any(), eq("PIPPINUSDT"), any(), eq(1000L))).thenReturn(List.of(historical));
        doAnswer(invocation -> {
            record.setRecoveryAttempts(5);
            return null;
        }).when(recordService).markRecoveryAttempt(record);

        var service = new ExecutionRecoveryService(records, credentials, gateway, recordService, ledger, new QuantProperties());
        service.recover();

        verify(gateway).queryOrderHistory(any(), eq("PIPPINUSDT"), any(), eq(1000L));
        verify(ledger).syncSnapshot(1L, historical);
        verify(recordService).markRecovered(record, 321L, "FILLED");
        verify(gateway, never()).placeOrder(any());
    }


    @Test
    void verifiedOpenAbsenceNeedsTwoCompleteChecksBeforeMarkingFailed() {
        var records = mock(QuantExecutionRecordMapper.class);
        var credentials = mock(QuantCredentialMapper.class);
        var gateway = mock(ExchangeGateway.class);
        var recordService = mock(ExecutionRecordService.class);
        var ledger = mock(OrderLedgerService.class);
        var record = new QuantExecutionRecord();
        record.setId(11L);
        record.setCredentialId(1L);
        record.setStrategyId(2L);
        record.setStrategyInstanceId(3L);
        record.setSymbol("BTCUSDT");
        record.setIdempotencyKey("strategy-open-absence");
        record.setClientOrderId("client-open-absence");
        record.setOperationType("OPEN");
        record.setExecutionStatus("UNKNOWN");
        record.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(5));
        record.setRecoveryAttempts(4);
        when(records.selectList(any())).thenReturn(List.of(record));
        var credential = new QuantCredential();
        credential.setApiKey("test");
        credential.setSecretKey("test");
        credential.setRunMode("TESTNET");
        when(credentials.selectById(1L)).thenReturn(credential);
        when(gateway.queryOrder(any(), eq("BTCUSDT"), isNull(), eq("client-open-absence"))).thenReturn(null);
        when(gateway.queryOrderHistoryComplete(any(), eq("BTCUSDT"), any(), eq(1000L))).thenReturn(List.of());
        when(gateway.queryOrders(any(), eq("BTCUSDT"))).thenReturn(List.of());
        doAnswer(invocation -> {
            record.setRecoveryAttempts(5);
            return null;
        }).when(recordService).markRecoveryAttempt(record);
        when(recordService.hasVerifiedAbsenceMatured(eq(record), any())).thenReturn(false);
        when(recordService.recordVerifiedAbsence(eq(record), anyString(), any())).thenReturn(false);

        var service = new ExecutionRecoveryService(records, credentials, gateway, recordService, ledger, new QuantProperties());
        service.recover();

        verify(gateway).queryOrderHistoryComplete(any(), eq("BTCUSDT"), any(), eq(1000L));
        verify(recordService).recordVerifiedAbsence(eq(record), contains("完整历史"), any());
        verify(gateway, never()).placeOrder(any());
    }

    @Test
    void secondVerifiedOpenAbsenceReleasesMatchingLiveTicketBeforeFinalizingExecution() {
        var records = mock(QuantExecutionRecordMapper.class);
        var credentials = mock(QuantCredentialMapper.class);
        var gateway = mock(ExchangeGateway.class);
        var recordService = mock(ExecutionRecordService.class);
        var ledger = mock(OrderLedgerService.class);
        var dispatcher = mock(LiveOrderDispatcher.class);
        var record = new QuantExecutionRecord();
        record.setId(12L);
        record.setCredentialId(1L);
        record.setStrategyId(2L);
        record.setStrategyInstanceId(3L);
        record.setSymbol("BTCUSDT");
        record.setIdempotencyKey("strategy-open-absence-live");
        record.setClientOrderId("client-open-absence-live");
        record.setOperationType("OPEN");
        record.setExecutionStatus("UNKNOWN");
        record.setErrorMessage("VERIFIED_ABSENCE_PENDING:previous complete check");
        record.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(2));
        record.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(5));
        record.setRecoveryAttempts(4);
        when(records.selectList(any())).thenReturn(List.of(record));
        var credential = new QuantCredential();
        credential.setApiKey("test");
        credential.setSecretKey("test");
        credential.setRunMode("LIVE");
        when(credentials.selectById(1L)).thenReturn(credential);
        when(gateway.queryOrder(any(), eq("BTCUSDT"), isNull(), eq("client-open-absence-live"))).thenReturn(null);
        when(gateway.queryOrderHistoryComplete(any(), eq("BTCUSDT"), any(), eq(1000L))).thenReturn(List.of());
        when(gateway.queryOrders(any(), eq("BTCUSDT"))).thenReturn(List.of());
        doAnswer(invocation -> {
            record.setRecoveryAttempts(5);
            return null;
        }).when(recordService).markRecoveryAttempt(record);
        when(recordService.hasVerifiedAbsenceMatured(eq(record), any())).thenReturn(true);
        when(dispatcher.verifiedNotExecuted(1L, "BTCUSDT", "strategy-open-absence-live", "client-open-absence-live", 3L, any()))
            .thenAnswer(invocation -> ((java.util.function.BooleanSupplier) invocation.getArgument(5)).getAsBoolean());
        when(recordService.recordVerifiedAbsence(eq(record), anyString(), any())).thenReturn(true);

        var service = new ExecutionRecoveryService(records, credentials, gateway, recordService, ledger, new QuantProperties());
        service.setLiveOrderDispatcher(dispatcher);
        service.recover();

        org.mockito.InOrder order = inOrder(dispatcher, recordService);
        order.verify(dispatcher).verifiedNotExecuted(1L, "BTCUSDT", "strategy-open-absence-live", "client-open-absence-live", 3L, any());
        order.verify(recordService).recordVerifiedAbsence(eq(record), anyString(), any());
        verify(gateway, never()).placeOrder(any());
    }

    @Test
    void incompleteHistoryCannotRecordVerifiedOpenAbsence() {
        var records = mock(QuantExecutionRecordMapper.class);
        var credentials = mock(QuantCredentialMapper.class);
        var gateway = mock(ExchangeGateway.class);
        var recordService = mock(ExecutionRecordService.class);
        var ledger = mock(OrderLedgerService.class);
        var record = new QuantExecutionRecord();
        record.setId(13L);
        record.setCredentialId(1L);
        record.setStrategyId(2L);
        record.setStrategyInstanceId(3L);
        record.setSymbol("BTCUSDT");
        record.setIdempotencyKey("strategy-open-incomplete-history");
        record.setClientOrderId("client-open-incomplete-history");
        record.setOperationType("OPEN");
        record.setExecutionStatus("UNKNOWN");
        record.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(5));
        record.setRecoveryAttempts(4);
        when(records.selectList(any())).thenReturn(List.of(record));
        var credential = new QuantCredential();
        credential.setApiKey("test");
        credential.setSecretKey("test");
        credential.setRunMode("TESTNET");
        when(credentials.selectById(1L)).thenReturn(credential);
        when(gateway.queryOrder(any(), eq("BTCUSDT"), isNull(), eq("client-open-incomplete-history"))).thenReturn(null);
        when(gateway.queryOrderHistoryComplete(any(), eq("BTCUSDT"), any(), eq(1000L)))
            .thenThrow(new IllegalStateException("history incomplete"));
        when(gateway.queryOrders(any(), eq("BTCUSDT"))).thenReturn(List.of());
        doAnswer(invocation -> {
            record.setRecoveryAttempts(5);
            return null;
        }).when(recordService).markRecoveryAttempt(record);

        var service = new ExecutionRecoveryService(records, credentials, gateway, recordService, ledger, new QuantProperties());
        service.recover();

        verify(recordService, never()).recordVerifiedAbsence(any(), anyString(), any());
        verify(recordService).markUnknown(eq(record), contains("90 秒"));
    }


    @Test
    void doesNotInferOpenAbsenceAfterExchangeOrderHistoryRetentionExpires() {
        var records = mock(QuantExecutionRecordMapper.class);
        var credentials = mock(QuantCredentialMapper.class);
        var gateway = mock(ExchangeGateway.class);
        var recordService = mock(ExecutionRecordService.class);
        var ledger = mock(OrderLedgerService.class);
        var record = new QuantExecutionRecord();
        record.setId(14L);
        record.setCredentialId(1L);
        record.setStrategyId(2L);
        record.setStrategyInstanceId(3L);
        record.setSymbol("BTCUSDT");
        record.setIdempotencyKey("strategy-open-expired-history");
        record.setClientOrderId("client-open-expired-history");
        record.setOperationType("OPEN");
        record.setExecutionStatus("UNKNOWN");
        record.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC).minusDays(4));
        record.setRecoveryAttempts(4);
        when(records.selectList(any())).thenReturn(List.of(record));
        var credential = new QuantCredential();
        credential.setApiKey("test");
        credential.setSecretKey("test");
        credential.setRunMode("TESTNET");
        when(credentials.selectById(1L)).thenReturn(credential);
        when(gateway.queryOrder(any(), eq("BTCUSDT"), isNull(), eq("client-open-expired-history"))).thenReturn(null);
        when(gateway.queryOrders(any(), eq("BTCUSDT"))).thenReturn(List.of());
        doAnswer(invocation -> {
            record.setRecoveryAttempts(5);
            return null;
        }).when(recordService).markRecoveryAttempt(record);

        var service = new ExecutionRecoveryService(records, credentials, gateway, recordService, ledger, new QuantProperties());
        service.recover();

        verify(gateway, never()).queryOrderHistoryComplete(any(), any(), any(), any());
        verify(recordService, never()).recordVerifiedAbsence(any(), anyString(), any());
    }

    @Test
    void nullUnresolvedScanDoesNotAttemptToReleaseStrandedDispatches() {
        var records = mock(QuantExecutionRecordMapper.class);
        when(records.selectList(any())).thenReturn(null);
        var service = new ExecutionRecoveryService(records, mock(QuantCredentialMapper.class), mock(ExchangeGateway.class),
            mock(ExecutionRecordService.class), mock(OrderLedgerService.class), new QuantProperties());
        service.setLiveOrderDispatcher(mock(LiveOrderDispatcher.class));

        service.recover();

        verify(records, never()).strandedRecoveredDispatches();
    }

}
