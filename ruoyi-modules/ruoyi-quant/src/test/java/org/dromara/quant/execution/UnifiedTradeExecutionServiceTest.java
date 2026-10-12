package org.dromara.quant.execution;

import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.risk.LiveTradingGate;
import org.dromara.quant.execution.domain.QuantExecutionRecord;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class UnifiedTradeExecutionServiceTest {
    private static org.dromara.quant.market.AccountBalanceCache balanceCache() {
        var cache = mock(org.dromara.quant.market.AccountBalanceCache.class);
        var snapshot = new org.dromara.quant.market.AccountBalanceSnapshot(1L, new BigDecimal("10000"), java.util.Set.of(),
            System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30));
        when(cache.requireSnapshot(1L)).thenReturn(snapshot);
        return cache;
    }

    private static TradingPreparationService preparation(ExchangeGateway gateway) {
        var preparation = mock(TradingPreparationService.class);
        var tokens = new java.util.HashMap<String, TradingPreparationService.Prepared>();
        when(preparation.requireReady(any(), anyString(), anyInt(), any())).thenAnswer(call ->
            tokens.computeIfAbsent(call.getArgument(1), symbol -> new TradingPreparationService.Prepared(
                new ExchangeGateway.PositionMode(false), gateway.queryTradingRule(null, symbol),
                System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60))));
        return preparation;
    }

    @Test
    void unpreparedEntryNeverChangesAccountSettingsOrPlacesOrder() {
        var gateway = mock(ExchangeGateway.class);
        var records = mock(ExecutionRecordService.class);
        when(records.tryCreatePending(any(), any(), any(), any(), any(), any(), any())).thenReturn(new QuantExecutionRecord());
        when(gateway.placeOrder(any())).thenReturn(new ExchangeGateway.OrderResult(1L, "test", "FILLED"));
        var rule = new ExchangeGateway.SymbolRule("BTCUSDT", "USDT", 2, 0, new BigDecimal("0.01"), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, "TRADING");
        var service = new UnifiedTradeExecutionService(gateway, mock(LiveTradingGate.class), records, mock(OrderLedgerService.class),
            mock(org.dromara.quant.risk.OpenCapacityService.class), mock(org.dromara.quant.market.AccountPositionCache.class), balanceCache(), mock(TradingPreparationService.class));
        assertThrows(org.dromara.quant.risk.OpenCapacityService.CapacityBlockedException.class,
            () -> service.open(new OpenPositionCommand(1L, 2L, 3L, null, "BTCUSDT", "BUY", "MARKET", BigDecimal.ONE,
                BigDecimal.TEN, null, "not-prepared", "TESTNET", rule, 1, "ISOLATED", null)));
        verify(gateway, never()).setMarginType(any(), any(), any());
        verify(gateway, never()).setLeverage(any(), any(), anyInt());
        verify(gateway, never()).placeOrder(any());
    }


    @Test
    void marketQuantityMustRespectBothLotFilters() {
        ExchangeGateway gateway = mock(ExchangeGateway.class);
        when(gateway.queryPrice(any())).thenReturn(new ExchangeGateway.PriceSnapshot("ONDOUSDT", BigDecimal.TEN, BigDecimal.TEN, java.time.Instant.now()));
        ExecutionRecordService records = mock(ExecutionRecordService.class);
        when(records.tryCreatePending(any(), any(), any(), any(), any(), any(), any())).thenReturn(new QuantExecutionRecord());
        when(gateway.placeOrder(any())).thenReturn(new ExchangeGateway.OrderResult(1L, "test", "FILLED"));
        var rule = new ExchangeGateway.SymbolRule("ONDOUSDT", "USDT", 2, 1, new BigDecimal("0.01"), new BigDecimal("0.2"), new BigDecimal("0.3"), BigDecimal.ONE, "TRADING");
        var service = new UnifiedTradeExecutionService(gateway, mock(LiveTradingGate.class), records, mock(OrderLedgerService.class), mock(org.dromara.quant.risk.OpenCapacityService.class), mock(org.dromara.quant.market.AccountPositionCache.class), balanceCache(), preparation(gateway));
        service.open(new OpenPositionCommand(1L, 2L, 3L, new ExchangeGateway.Credential("test", "test", "HMAC", "TESTNET"),
            "ONDOUSDT", "BUY", "MARKET", new BigDecimal("3.5"), BigDecimal.TEN, null, "test", "TESTNET", rule, null, null, null));
        verify(gateway).placeOrder(argThat(request -> new BigDecimal("3.0").compareTo(request.quantity()) == 0));
    }
    @Test
    void explicitRejectionIsFailedButTimeoutRemainsUnknown() {
        for (boolean rejected : new boolean[] {true, false}) {
            ExchangeGateway gateway = mock(ExchangeGateway.class);
        when(gateway.queryPrice(any())).thenReturn(new ExchangeGateway.PriceSnapshot("ONDOUSDT", BigDecimal.TEN, BigDecimal.TEN, java.time.Instant.now()));
            ExecutionRecordService records = mock(ExecutionRecordService.class);
            QuantExecutionRecord record = new QuantExecutionRecord();
            when(records.tryCreatePending(any(), any(), any(), any(), any(), any(), any())).thenReturn(record);
            RuntimeException failure = rejected ? new org.dromara.quant.exchange.ExchangeTradingPort.OrderRejectedException(-4023, "step size")
                : new IllegalStateException("timeout", new java.net.SocketTimeoutException());
            when(gateway.placeOrder(any())).thenThrow(failure);
            var rule = new ExchangeGateway.SymbolRule("ONDOUSDT", "USDT", 2, 1, new BigDecimal("0.01"), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, "TRADING");
            var service = new UnifiedTradeExecutionService(gateway, mock(LiveTradingGate.class), records, mock(OrderLedgerService.class), mock(org.dromara.quant.risk.OpenCapacityService.class), mock(org.dromara.quant.market.AccountPositionCache.class), balanceCache(), preparation(gateway));
            assertThrows(RuntimeException.class, () -> service.open(new OpenPositionCommand(1L, 2L, 3L,
                new ExchangeGateway.Credential("test", "test", "HMAC", "TESTNET"), "ONDOUSDT", "BUY", "MARKET", BigDecimal.TEN,
                BigDecimal.TEN, null, "test", "TESTNET", rule, null, null, null)));
            if (rejected) { verify(records).failed(eq(record), any()); verify(records, never()).markUnknown(any(), any()); }
            else { verify(records).markUnknown(eq(record), any()); verify(records, never()).failed(any(), any()); }
        }
    }

    @Test
    void explicitlyUnsentOrderReleasesCapacityInsteadOfBecomingUnknown() {
        var gateway = mock(ExchangeGateway.class);
        var records = mock(ExecutionRecordService.class);
        var capacity = mock(org.dromara.quant.risk.OpenCapacityService.class);
        var record = new QuantExecutionRecord();
        when(records.tryCreatePending(any(), any(), any(), any(), any(), any(), any())).thenReturn(record);
        when(gateway.placeOrder(any())).thenThrow(new org.dromara.quant.exchange.ExchangeTradingPort.OrderNotSentException("expired before HTTP"));
        var rule = new ExchangeGateway.SymbolRule("BTCUSDT", "USDT", 2, 0, new BigDecimal("0.01"), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, "TRADING");
        var service = new UnifiedTradeExecutionService(gateway, mock(LiveTradingGate.class), records, mock(OrderLedgerService.class), capacity,
            mock(org.dromara.quant.market.AccountPositionCache.class), balanceCache(), preparation(gateway));
        assertThrows(org.dromara.quant.exchange.ExchangeTradingPort.OrderNotSentException.class, () -> service.open(
            new OpenPositionCommand(1L, 2L, 3L, null, "BTCUSDT", "BUY", "MARKET", BigDecimal.ONE, BigDecimal.TEN, null,
                "unsent", "TESTNET", rule, 1, "ISOLATED", null)));
        verify(records).failed(eq(record), any());
        verify(records, never()).markUnknown(any(), any());
        verify(capacity).releaseFailed(1L, "unsent");
    }

    @Test
    void actualEnvironmentRuleOverridesLocalRuleBeforeOrder() {
        ExchangeGateway gateway = mock(ExchangeGateway.class);
        when(gateway.queryPrice(any())).thenReturn(new ExchangeGateway.PriceSnapshot("ONDOUSDT", BigDecimal.TEN, BigDecimal.TEN, java.time.Instant.now()));
        ExecutionRecordService records = mock(ExecutionRecordService.class);
        when(records.tryCreatePending(any(), any(), any(), any(), any(), any(), any())).thenReturn(new QuantExecutionRecord());
        var local = new ExchangeGateway.SymbolRule("ONDOUSDT", "USDT", 2, 2, new BigDecimal("0.01"), new BigDecimal("0.01"), new BigDecimal("0.01"), BigDecimal.ONE, "TRADING");
        var actual = new ExchangeGateway.SymbolRule("ONDOUSDT", "USDT", 2, 0, new BigDecimal("0.01"), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, "TRADING");
        when(gateway.queryTradingRule(any(), eq("ONDOUSDT"))).thenReturn(actual);
        when(gateway.placeOrder(any())).thenReturn(new ExchangeGateway.OrderResult(1L, "test", "FILLED"));
        var service = new UnifiedTradeExecutionService(gateway, mock(LiveTradingGate.class), records, mock(OrderLedgerService.class), mock(org.dromara.quant.risk.OpenCapacityService.class), mock(org.dromara.quant.market.AccountPositionCache.class), balanceCache(), preparation(gateway));
        service.open(new OpenPositionCommand(1L, 2L, 3L, new ExchangeGateway.Credential("test", "test", "HMAC", "TESTNET"), "ONDOUSDT", "BUY", "MARKET",
            new BigDecimal("3.45"), BigDecimal.TEN, null, "test", "TESTNET", local, null, null, null));
        verify(gateway).placeOrder(argThat(request -> new BigDecimal("3").compareTo(request.quantity()) == 0));
    }

    @Test
    void capacityRejectionNeverReachesExchangeAndMarksExecutionFailed() {
        var gateway = mock(ExchangeGateway.class);
        var records = mock(ExecutionRecordService.class);
        var capacity = mock(org.dromara.quant.risk.OpenCapacityService.class);
        var record = new QuantExecutionRecord();
        when(records.tryCreatePending(any(), any(), any(), any(), any(), any(), any())).thenReturn(record);
        doThrow(new org.dromara.quant.risk.OpenCapacityService.CapacityBlockedException("策略最大持仓数已达到"))
            .when(capacity).reserve(any(), any(), any(), any());
        var service = new UnifiedTradeExecutionService(gateway, mock(LiveTradingGate.class), records, mock(OrderLedgerService.class), capacity, mock(org.dromara.quant.market.AccountPositionCache.class), balanceCache(), preparation(gateway));
        var command = new OpenPositionCommand(1L, 2L, 3L, null, "BTCUSDT", "BUY", "MARKET", BigDecimal.ONE,
            BigDecimal.TEN, null, "blocked", "TESTNET", null, 1, "ISOLATED", null);
        assertThrows(org.dromara.quant.risk.OpenCapacityService.CapacityBlockedException.class, () -> service.open(command));
        verify(gateway, never()).placeOrder(any());
        verify(gateway, never()).setLeverage(any(), any(), anyInt());
        verify(records).failed(eq(record), contains("策略最大持仓数"));
    }

    @Test
    void expiredSignalNeverReachesExchange() {
        var gateway = mock(ExchangeGateway.class);
        var records = mock(ExecutionRecordService.class);
        when(records.tryCreatePending(any(), any(), any(), any(), any(), any(), any())).thenReturn(new QuantExecutionRecord());
        var service = new UnifiedTradeExecutionService(gateway, mock(LiveTradingGate.class), records, mock(OrderLedgerService.class), mock(org.dromara.quant.risk.OpenCapacityService.class), mock(org.dromara.quant.market.AccountPositionCache.class), balanceCache(), preparation(gateway));
        var command = new OpenPositionCommand(1L, 2L, 3L, null, "BTCUSDT", "BUY", "MARKET", BigDecimal.ONE,
            null, null, "expired", "TESTNET", null, 1, "ISOLATED", null, BigDecimal.TEN, java.time.Instant.now().minusSeconds(1));
        assertThrows(org.dromara.quant.risk.OpenCapacityService.CapacityBlockedException.class, () -> service.open(command));
        verify(gateway, never()).queryPrice(any());
        verify(gateway, never()).placeOrder(any());
    }

    @Test
    void healthyAccountChecksUseStreamCacheAndNeverQueryRest() {
        var gateway = mock(ExchangeGateway.class);
        var records = mock(ExecutionRecordService.class);
        when(records.tryCreatePending(any(), any(), any(), any(), any(), any(), any())).thenReturn(new QuantExecutionRecord());
        var cache = mock(org.dromara.quant.market.AccountPositionCache.class);
        when(cache.requirePositions(1L)).thenReturn(java.util.List.of());
        var service = new UnifiedTradeExecutionService(gateway, mock(LiveTradingGate.class), records,
            mock(OrderLedgerService.class), mock(org.dromara.quant.risk.OpenCapacityService.class), cache, balanceCache(), preparation(gateway));
        when(gateway.placeOrder(any())).thenReturn(new ExchangeGateway.OrderResult(1L, "test", "FILLED"));
        service.open(new OpenPositionCommand(1L, 2L, 3L, null, "BTCUSDT", "BUY", "MARKET", BigDecimal.ONE,
            BigDecimal.TEN, null, "stream", "TESTNET",
            new ExchangeGateway.SymbolRule("BTCUSDT", "USDT", 2, 0, new BigDecimal("0.01"), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, "TRADING"), null, null, null));
        verify(cache, times(2)).requirePositions(1L);
        verify(gateway, never()).queryPositions(any(), any());
        verify(gateway).placeOrder(any());
    }

    @Test
    void disconnectedAccountNeverReservesCapacityOrPlacesOrder() {
        var gateway = mock(ExchangeGateway.class);
        var records = mock(ExecutionRecordService.class);
        var capacity = mock(org.dromara.quant.risk.OpenCapacityService.class);
        var cache = mock(org.dromara.quant.market.AccountPositionCache.class);
        when(records.tryCreatePending(any(), any(), any(), any(), any(), any(), any())).thenReturn(new QuantExecutionRecord());
        when(cache.requirePositions(1L)).thenThrow(new org.dromara.quant.risk.OpenCapacityService.CapacityBlockedException("账户持仓流未就绪"));
        var service = new UnifiedTradeExecutionService(gateway, mock(LiveTradingGate.class), records, mock(OrderLedgerService.class), capacity, cache, balanceCache(), preparation(gateway));
        assertThrows(org.dromara.quant.risk.OpenCapacityService.CapacityBlockedException.class,
            () -> service.open(new OpenPositionCommand(1L, 2L, 3L, null, "BTCUSDT", "BUY", "MARKET", BigDecimal.ONE,
                BigDecimal.TEN, null, "offline", "TESTNET", null, null, null, null)));
        verify(capacity, never()).reserve(any(), any(), any(), any());
        verify(gateway, never()).placeOrder(any());
        verify(gateway, never()).queryPositions(any(), any());
    }

    @Test
    void connectionLostDuringRuleChecksPreventsOrderEvenAfterCapacityReservation() {
        var gateway = mock(ExchangeGateway.class);
        var records = mock(ExecutionRecordService.class);
        var capacity = mock(org.dromara.quant.risk.OpenCapacityService.class);
        var cache = mock(org.dromara.quant.market.AccountPositionCache.class);
        var record = new QuantExecutionRecord();
        when(records.tryCreatePending(any(), any(), any(), any(), any(), any(), any())).thenReturn(record);
        when(cache.requirePositions(1L)).thenReturn(java.util.List.of())
            .thenThrow(new org.dromara.quant.risk.OpenCapacityService.CapacityBlockedException("账户持仓流断线"));
        var service = new UnifiedTradeExecutionService(gateway, mock(LiveTradingGate.class), records, mock(OrderLedgerService.class), capacity, cache, balanceCache(), preparation(gateway));
        var rule = new ExchangeGateway.SymbolRule("BTCUSDT", "USDT", 2, 0, new BigDecimal("0.01"), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, "TRADING");
        assertThrows(org.dromara.quant.risk.OpenCapacityService.CapacityBlockedException.class,
            () -> service.open(new OpenPositionCommand(1L, 2L, 3L, null, "BTCUSDT", "BUY", "MARKET", BigDecimal.ONE,
                BigDecimal.TEN, null, "lost", "TESTNET", rule, null, null, null)));
        verify(capacity).reserve(any(), any(), any(), any());
        verify(capacity).releaseFailed(1L, "lost");
        verify(records).failed(eq(record), contains("断线"));
        verify(gateway, never()).placeOrder(any());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"TESTNET", "LIVE"})
    void entrySwitchMustNotBlockReduceOnlyClose(String mode) {
        var gateway = mock(ExchangeGateway.class);
        var records = mock(ExecutionRecordService.class);
        when(records.tryCreatePending(any(), any(), any(), any(), any(), any(), any())).thenReturn(new QuantExecutionRecord());
        var rule = new ExchangeGateway.SymbolRule("BTCUSDT", "USDT", 2, 0, new BigDecimal("0.01"), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, "TRADING");
        when(gateway.queryTradingRule(any(), any())).thenReturn(rule);
        when(gateway.placeOrder(any())).thenReturn(new ExchangeGateway.OrderResult(55L, "close", "FILLED"));
        var service = new UnifiedTradeExecutionService(gateway, new LiveTradingGate(), records, mock(OrderLedgerService.class),
            mock(org.dromara.quant.risk.OpenCapacityService.class), mock(org.dromara.quant.market.AccountPositionCache.class), balanceCache(), preparation(gateway));
        var safety = new ExecutionSafetyContext(false, true, true, true, true, true, false, false);
        service.close(new ClosePositionCommand(1L, 2L, 3L, new ExchangeGateway.Credential("test", "test", "HMAC", mode),
            "BTCUSDT", "SELL", "MARKET", BigDecimal.ONE, null, "BOTH", "close", mode, rule, safety));
        verify(gateway).placeOrder(argThat(r -> r.reduceOnly() && r.live() && r.quantity().compareTo(BigDecimal.ONE) == 0));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"accountDisabled", "unauthorized", "noPermission", "offline"})
    void liveCloseStillRequiresAccountPermissionAndAuthorization(String failure) {
        var gateway = mock(ExchangeGateway.class);
        var records = mock(ExecutionRecordService.class);
        when(records.tryCreatePending(any(), any(), any(), any(), any(), any(), any())).thenReturn(new QuantExecutionRecord());
        var rule = new ExchangeGateway.SymbolRule("BTCUSDT", "USDT", 2, 0, new BigDecimal("0.01"), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, "TRADING");
        var safety = new ExecutionSafetyContext(false, !failure.equals("accountDisabled"), !failure.equals("noPermission"), true,
            !failure.equals("offline"), !failure.equals("unauthorized"), false, false);
        var service = new UnifiedTradeExecutionService(gateway, new LiveTradingGate(), records, mock(OrderLedgerService.class),
            mock(org.dromara.quant.risk.OpenCapacityService.class), mock(org.dromara.quant.market.AccountPositionCache.class), balanceCache(), preparation(gateway));
        assertThrows(LiveTradingGate.LiveTradingBlockedException.class, () -> service.close(new ClosePositionCommand(1L, 2L, 3L,
            new ExchangeGateway.Credential("test", "test", "HMAC", "LIVE"), "BTCUSDT", "SELL", "MARKET", BigDecimal.ONE,
            null, "BOTH", "close", "LIVE", rule, safety)));
        verify(gateway, never()).placeOrder(any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"TESTNET", "LIVE"})
    void enabledModesUseSameEntryQuantityAndPositionSide(String mode) {
        var gateway = mock(ExchangeGateway.class);
        var records = mock(ExecutionRecordService.class);
        when(records.tryCreatePending(any(), any(), any(), any(), any(), any(), any())).thenReturn(new QuantExecutionRecord());
        var rule = new ExchangeGateway.SymbolRule("BTCUSDT", "USDT", 2, 0, new BigDecimal("0.01"), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, "TRADING");
        when(gateway.placeOrder(any())).thenReturn(new ExchangeGateway.OrderResult(55L, "open", "FILLED"));
        var service = new UnifiedTradeExecutionService(gateway, new LiveTradingGate(), records, mock(OrderLedgerService.class),
            mock(org.dromara.quant.risk.OpenCapacityService.class), mock(org.dromara.quant.market.AccountPositionCache.class), balanceCache(), preparation(gateway));
        var safety = new ExecutionSafetyContext(true, true, true, true, true, true, true, true);
        service.open(new OpenPositionCommand(1L, 2L, 3L, new ExchangeGateway.Credential("test", "test", "HMAC", mode),
            "BTCUSDT", "BUY", "MARKET", new BigDecimal("3.5"), BigDecimal.TEN, null, "open", mode, rule, 10, "ISOLATED", safety));
        verify(gateway).placeOrder(argThat(r -> r.live() && !r.reduceOnly() && "BOTH".equals(r.positionSide())
            && r.quantity().compareTo(new BigDecimal("3")) == 0));
        verify(gateway, never()).setMarginType(any(), any(), any());
        verify(gateway, never()).setLeverage(any(), any(), anyInt());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"missing", "incomplete"})
    void liveCloseUsesExchangeRulesWhenImportedRuleIsUnavailable(String localRule) {
        var gateway = mock(ExchangeGateway.class);
        var records = mock(ExecutionRecordService.class);
        when(records.tryCreatePending(any(), any(), any(), any(), any(), any(), any())).thenReturn(new QuantExecutionRecord());
        var actual = new ExchangeGateway.SymbolRule("BTCUSDT", "USDT", 2, 0, new BigDecimal("0.01"), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, "TRADING");
        when(gateway.queryTradingRule(any(), any())).thenReturn(actual);
        when(gateway.placeOrder(any())).thenReturn(new ExchangeGateway.OrderResult(55L, "close", "FILLED"));
        var service = new UnifiedTradeExecutionService(gateway, new LiveTradingGate(), records, mock(OrderLedgerService.class),
            mock(org.dromara.quant.risk.OpenCapacityService.class), mock(org.dromara.quant.market.AccountPositionCache.class), balanceCache(), preparation(gateway));
        var imported = "missing".equals(localRule) ? null : new ExchangeGateway.SymbolRule("BTCUSDT", "USDT", 2, 0,
            new BigDecimal("0.01"), new BigDecimal("0.1"), new BigDecimal("0.1"), null, "TRADING");
        var safety = new ExecutionSafetyContext(false, true, true, true, true, true, false, false);
        var result = service.close(new ClosePositionCommand(1L, 2L, 3L,
            new ExchangeGateway.Credential("test", "test", "HMAC", "LIVE"), "BTCUSDT", "SELL", "MARKET",
            new BigDecimal("1.5"), null, "BOTH", "close-actual-rule", "LIVE", imported, safety));
        assertTrue(result.accepted());
        verify(gateway).placeOrder(argThat(request -> request.reduceOnly() && request.quantity().compareTo(BigDecimal.ONE) == 0));
    }

    @Test
    void disabledLiveEntryStillNeverSendsAnOrder() {
        var gateway = mock(ExchangeGateway.class);
        var records = mock(ExecutionRecordService.class);
        when(records.tryCreatePending(any(), any(), any(), any(), any(), any(), any())).thenReturn(new QuantExecutionRecord());
        var rule = new ExchangeGateway.SymbolRule("BTCUSDT", "USDT", 2, 0, new BigDecimal("0.01"), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, "TRADING");
        var service = new UnifiedTradeExecutionService(gateway, new LiveTradingGate(), records, mock(OrderLedgerService.class),
            mock(org.dromara.quant.risk.OpenCapacityService.class), mock(org.dromara.quant.market.AccountPositionCache.class), balanceCache(), preparation(gateway));
        assertThrows(LiveTradingGate.LiveTradingBlockedException.class, () -> service.open(new OpenPositionCommand(1L, 2L, 3L,
            new ExchangeGateway.Credential("test", "test", "HMAC", "LIVE"), "BTCUSDT", "BUY", "MARKET", BigDecimal.ONE,
            BigDecimal.TEN, null, "blocked-live", "LIVE", rule, 10, "ISOLATED",
            new ExecutionSafetyContext(false, true, true, true, true, true, true, true))));
        verify(gateway, never()).placeOrder(any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"LONG", "SHORT", ""})
    void closeRejectsAnAmbiguousOrOpeningDirection(String positionSide) {
        var gateway = mock(ExchangeGateway.class);
        var service = new UnifiedTradeExecutionService(gateway, new LiveTradingGate(), mock(ExecutionRecordService.class), mock(OrderLedgerService.class),
            mock(org.dromara.quant.risk.OpenCapacityService.class), mock(org.dromara.quant.market.AccountPositionCache.class), balanceCache(), preparation(gateway));
        assertThrows(IllegalArgumentException.class, () -> service.close(new ClosePositionCommand(1L, 2L, 3L,
            new ExchangeGateway.Credential("test", "test", "HMAC", "LIVE"), "BTCUSDT", "SHORT".equals(positionSide) ? "SELL" : "BUY",
            "MARKET", BigDecimal.ONE, null, positionSide, "invalid-close", "LIVE", null, null)));
        verify(gateway, never()).placeOrder(any());
    }

}
