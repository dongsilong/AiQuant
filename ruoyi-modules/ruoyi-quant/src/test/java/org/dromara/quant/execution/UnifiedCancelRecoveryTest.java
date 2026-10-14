package org.dromara.quant.execution;

import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.execution.domain.QuantExecutionRecord;
import org.dromara.quant.risk.LiveTradingGate;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class UnifiedCancelRecoveryTest {
    private final ExchangeGateway gateway = mock(ExchangeGateway.class);
    private final ExecutionRecordService records = mock(ExecutionRecordService.class);
    private final OrderLedgerService ledger = mock(OrderLedgerService.class);
    private final LiveOrderDispatcher dispatcher = mock(LiveOrderDispatcher.class);
    private final QuantExecutionRecord record = new QuantExecutionRecord();
    private final ExchangeGateway.Credential credential =
        new ExchangeGateway.Credential("test", "test", "HMAC", "LIVE");

    private UnifiedTradeExecutionService service() {
        when(records.tryCreatePending(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(record);
        when(dispatcher.begin(anyLong(), anyString(), anyString(), anyString(), anyString(),
            org.mockito.ArgumentMatchers.<Long>nullable(Long.class),
            org.mockito.ArgumentMatchers.<Long>nullable(Long.class)))
            .thenAnswer(inv -> new LiveOrderDispatcher.Ticket(
                inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)));
        var service = new UnifiedTradeExecutionService(gateway, new LiveTradingGate(), records, ledger,
            mock(org.dromara.quant.risk.OpenCapacityService.class),
            mock(org.dromara.quant.market.AccountPositionCache.class),
            mock(org.dromara.quant.market.AccountBalanceCache.class),
            mock(TradingPreparationService.class));
        service.setLiveDispatcher(dispatcher);
        return service;
    }

    private CancelOrderCommand command(String clientId) {
        return new CancelOrderCommand(1L, credential, "BTCUSDT", 99L, clientId, "cancel-99", "LIVE");
    }

    private ExchangeGateway.OrderSnapshot exchange(String clientId, String status, String filled) {
        return new ExchangeGateway.OrderSnapshot("BTCUSDT", 99L, clientId,
            "BUY", "LIMIT", status, BigDecimal.TEN, BigDecimal.ONE,
            new BigDecimal(filled), Instant.EPOCH);
    }

    @Test
    void cancelAckOnlyAfterPreciseTerminalSnapshotAndLedgerPersistence() {
        var service = service();
        var snapshot = exchange("cid-99", "CANCELED", "0.25");
        when(gateway.queryOrder(any(), eq("BTCUSDT"), eq(99L), eq("cid-99"))).thenReturn(snapshot);
        var result = service.cancel(command("cid-99"));
        assertEquals("CANCELED", result.status());
        var sequence = inOrder(gateway, ledger, records, dispatcher);
        sequence.verify(dispatcher).begin(eq(1L), eq("BTCUSDT"), eq("cancel-99"),
            eq("CANCEL"), eq("cid-99"), isNull(), isNull());
        sequence.verify(gateway).cancelOrder(any());
        sequence.verify(gateway).queryOrder(any(), eq("BTCUSDT"), eq(99L), eq("cid-99"));
        sequence.verify(ledger).syncSnapshot(1L, snapshot);
        sequence.verify(records).accepted(record, 99L, "CANCELED");
        sequence.verify(dispatcher).acknowledged(any(), eq(99L));
        verify(dispatcher, never()).unknown(any());
    }

    @Test
    void partialFillIsNotTerminalAndMustNotUnlockDispatch() {
        var service = service();
        when(gateway.queryOrder(any(), eq("BTCUSDT"), eq(99L), eq("cid-99")))
            .thenReturn(exchange("cid-99", "PARTIALLY_FILLED", "0.5"));
        assertThrows(IllegalStateException.class, () -> service.cancel(command("cid-99")));
        verify(dispatcher).unknown(any());
        verify(dispatcher, never()).acknowledged(any(), any());
        verifyNoInteractions(ledger);
        verify(records).markUnknown(eq(record), anyString());
    }

    @Test
    void timeoutAfterCancelRequestCannotReleaseGuard() {
        var service = service();
        doThrow(new IllegalStateException("timeout")).when(gateway).cancelOrder(any());
        assertThrows(IllegalStateException.class, () -> service.cancel(command("cid-99")));
        verify(dispatcher).unknown(any());
        verify(dispatcher, never()).acknowledged(any(), any());
    }

    @Test
    void missingClientIdResolvedFromVerifiedExchangeOrderBeforeCancel() {
        var service = service();
        var snapshot = exchange("verified-client", "FILLED", "1");
        when(gateway.queryOrder(any(), eq("BTCUSDT"), eq(99L), isNull())).thenReturn(snapshot);
        when(gateway.queryOrder(any(), eq("BTCUSDT"), eq(99L), eq("verified-client")))
            .thenReturn(snapshot);
        assertEquals("FILLED", service.cancel(command(null)).status());
        verify(records).tryCreatePending(eq(1L), isNull(), isNull(),
            eq("CANCEL"), eq("BTCUSDT"), eq("cancel-99"), eq("verified-client"));
        verify(dispatcher).begin(eq(1L), eq("BTCUSDT"), eq("cancel-99"),
            eq("CANCEL"), eq("verified-client"), isNull(), isNull());
    }

    @Test
    void mismatchedExchangeOrderIdentityNeverUnlocks() {
        var service = service();
        when(gateway.queryOrder(any(), eq("BTCUSDT"), eq(99L), eq("cid-99")))
            .thenReturn(exchange("different-client", "CANCELED", "0"));
        assertThrows(IllegalStateException.class, () -> service.cancel(command("cid-99")));
        verify(dispatcher).unknown(any());
        verify(dispatcher, never()).acknowledged(any(), any());
    }

    @Test
    void terminalRuleRequiresActualExchangeStatusAndExecutedQuantity() {
        assertFalse(UnifiedTradeExecutionService.verifiedTerminalCancel(
            exchange("cid-99", "NEW", "0"), "BTCUSDT", 99L, "cid-99"));
        assertFalse(UnifiedTradeExecutionService.verifiedTerminalCancel(
            exchange("cid-99", "PARTIALLY_FILLED", "0.3"), "BTCUSDT", 99L, "cid-99"));
        assertTrue(UnifiedTradeExecutionService.verifiedTerminalCancel(
            exchange("cid-99", "CANCELED", "0.3"), "BTCUSDT", 99L, "cid-99"));
        assertFalse(UnifiedTradeExecutionService.verifiedTerminalCancel(
            exchange("other", "FILLED", "1"), "BTCUSDT", 99L, "cid-99"));
    }
}
