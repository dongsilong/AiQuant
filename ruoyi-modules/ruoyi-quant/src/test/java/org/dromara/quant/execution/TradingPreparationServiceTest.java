package org.dromara.quant.execution;

import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.market.AccountBalanceCache;
import org.dromara.quant.market.AccountBalanceSnapshot;
import org.dromara.quant.market.AccountPositionCache;
import org.dromara.quant.risk.LiveTradingGate;
import org.dromara.quant.risk.OpenCapacityService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class TradingPreparationServiceTest {
    private final ExchangeGateway gateway = mock(ExchangeGateway.class);
    private final AccountPositionCache positions = mock(AccountPositionCache.class);
    private final AccountBalanceCache balances = mock(AccountBalanceCache.class);
    private final LiveTradingGate gate = new LiveTradingGate();
    private final TradingPreparationService service = new TradingPreparationService(gateway, gate, positions, balances);
    private final ExchangeGateway.Credential testnet = new ExchangeGateway.Credential("test", "test", "HMAC", "TESTNET");

    private void ready() {
        when(positions.requirePositions(1L)).thenReturn(List.of());
        when(balances.requireSnapshot(1L)).thenReturn(new AccountBalanceSnapshot(1L, BigDecimal.TEN, Set.of(), System.nanoTime() + TimeUnit.SECONDS.toNanos(30)));
        when(gateway.queryPositionMode(any())).thenReturn(new ExchangeGateway.PositionMode(true));
        when(gateway.querySymbolTradingSettings(any(), eq("BTCUSDT"))).thenReturn(new org.dromara.quant.exchange.ExchangeAccountPort.SymbolTradingSettings("BTCUSDT", "ISOLATED",
            10));
        when(gateway.queryTradingRule(any(), any())).thenReturn(new ExchangeGateway.SymbolRule("BTCUSDT", "USDT", 2, 3, new BigDecimal("0.01"), new BigDecimal("0.001"),
            new BigDecimal("0.001"), BigDecimal.ONE, "TRADING"));
    }

    @Test
    void warmupChangesSettingsOnceAndEntryReadsNeverUseRest() throws Exception {
        ready();
        assertThrows(OpenCapacityService.CapacityBlockedException.class, () -> service.requireReady(1L, "BTCUSDT", 10, "ISOLATED"));
        service.request(1L, testnet, "BTCUSDT", 10, "ISOLATED", null).get(2, TimeUnit.SECONDS);
        var token = service.requireReady(1L, "BTCUSDT", 10, "ISOLATED");
        assertTrue(token.positionMode().hedgeMode());
        for (int i = 0; i < 20; i++) {
            assertSame(token, service.requireReady(1L, "BTCUSDT", 10, "ISOLATED"));
        }
        service.request(1L, testnet, "BTCUSDT", 10, "ISOLATED", null).get(2, TimeUnit.SECONDS);
        verify(gateway, times(1)).queryPositionMode(testnet);
        verify(gateway, times(1)).setMarginType(testnet, "BTCUSDT", "ISOLATED");
        verify(gateway, times(1)).setLeverage(testnet, "BTCUSDT", 10);
    }

    @Test
    void disabledLiveGateNeverChangesAccountSettings() throws Exception {
        ready();
        var live = new ExchangeGateway.Credential("test", "test", "HMAC", "LIVE");
        var safety = new ExecutionSafetyContext(false, true, true, true, true, true, true, true);
        service.request(1L, live, "BTCUSDT", 10, "ISOLATED", safety).get(2, TimeUnit.SECONDS);
        var blocked = assertThrows(LiveTradingGate.LiveTradingBlockedException.class, () -> service.requireReady(1L, "BTCUSDT", 10, "ISOLATED"));
        assertTrue(blocked.getFailures().contains("系统未开启实盘"));
        verify(gateway, never()).setMarginType(any(), any(), any());
        verify(gateway, never()).setLeverage(any(), any(), anyInt());
    }

    @Test
    void existingPositionNeverHasMarginOrLeverageChangedByWarmup() throws Exception {
        ready();
        when(positions.requirePositions(1L)).thenReturn(List.of(new ExchangeGateway.PositionSnapshot("BTCUSDT", "LONG", BigDecimal.ONE, BigDecimal.TEN, BigDecimal.TEN,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE, 10, null, null)));
        service.request(1L, testnet, "BTCUSDT", 10, "ISOLATED", null).get(2, TimeUnit.SECONDS);
        assertThrows(OpenCapacityService.CapacityBlockedException.class, () -> service.requireReady(1L, "BTCUSDT", 10, "ISOLATED"));
        verify(gateway, never()).setMarginType(any(), any(), any());
        verify(gateway, never()).setLeverage(any(), any(), anyInt());
    }

    @Test
    void ownAcknowledgementKeepsTokenButExternalSettingsOrDisconnectInvalidateIt() throws Exception {
        ready();
        service.request(1L, testnet, "BTCUSDT", 10, "ISOLATED", null).get(2, TimeUnit.SECONDS);
        var token = service.requireReady(1L, "BTCUSDT", 10, "ISOLATED");
        service.onEvent(1L, new ExchangeGateway.UserDataEvent("ACCOUNT_CONFIG_UPDATE", Instant.now(), "{\"ac\":{\"s\":\"BTCUSDT\",\"l\":10}}"));
        assertSame(token, service.requireReady(1L, "BTCUSDT", 10, "ISOLATED"));
        service.onEvent(1L, new ExchangeGateway.UserDataEvent("ACCOUNT_CONFIG_UPDATE", Instant.now(), "{\"ac\":{\"s\":\"BTCUSDT\",\"l\":20}}"));
        assertThrows(OpenCapacityService.CapacityBlockedException.class, () -> service.requireReady(1L, "BTCUSDT", 10, "ISOLATED"));
        service.request(1L, testnet, "BTCUSDT", 10, "ISOLATED", null).get(2, TimeUnit.SECONDS);
        service.invalidate(1L);
        assertThrows(OpenCapacityService.CapacityBlockedException.class, () -> service.requireReady(1L, "BTCUSDT", 10, "ISOLATED"));
    }

    @Test
    void changedDesiredSettingsCannotReuseOldToken() throws Exception {
        ready();
        service.request(1L, testnet, "BTCUSDT", 10, "ISOLATED", null).get(2, TimeUnit.SECONDS);
        assertThrows(OpenCapacityService.CapacityBlockedException.class, () -> service.requireReady(1L, "BTCUSDT", 20, "CROSSED"));
        service.request(1L, testnet, "BTCUSDT", 20, "CROSSED", null).get(2, TimeUnit.SECONDS);
        service.requireReady(1L, "BTCUSDT", 20, "CROSSED");
        verify(gateway).setMarginType(testnet, "BTCUSDT", "CROSSED");
        verify(gateway).setLeverage(testnet, "BTCUSDT", 20);
    }

    private LiveOrderDispatcher liveDispatcher() {
        var dispatcher = mock(LiveOrderDispatcher.class);
        when(dispatcher.beginConfiguration(eq(1L), eq("BTCUSDT"), anyString(), eq("ISOLATED"), eq(10))).thenAnswer(inv -> new LiveOrderDispatcher.Ticket(1L, "BTCUSDT",
            inv.getArgument(2)));
        return dispatcher;
    }

    private ExchangeGateway.Credential liveCredential() {
        return new ExchangeGateway.Credential("test", "test", "HMAC", "LIVE");
    }

    private ExecutionSafetyContext approvedLive() {
        return new ExecutionSafetyContext(true, true, true, true, true, true, true, true);
    }

    @Test
    void liveConfigHoldsOneDurableSlotAcrossMarginAndLeverageMutations() throws Exception {
        ready();
        var dispatcher = liveDispatcher();
        service.setLiveOrderDispatcher(dispatcher);
        service.request(1L, liveCredential(), "BTCUSDT", 10, "ISOLATED", approvedLive()).get(3, TimeUnit.SECONDS);
        service.requireReady(1L, "BTCUSDT", 10, "ISOLATED");
        var sequence = inOrder(dispatcher, gateway);
        sequence.verify(dispatcher).beginConfiguration(eq(1L), eq("BTCUSDT"), anyString(), eq("ISOLATED"), eq(10));
        sequence.verify(gateway).setMarginType(any(), eq("BTCUSDT"), eq("ISOLATED"));
        sequence.verify(gateway).setLeverage(any(), eq("BTCUSDT"), eq(10));
        sequence.verify(dispatcher).configurationApplied(any(LiveOrderDispatcher.Ticket.class));
        verify(dispatcher, never()).unknown(any());
    }

    @Test
    void liveRenewalRejectsOutOfBandLeverageDriftWithoutRewritingSettings() throws Exception {
        ready();
        var dispatcher = liveDispatcher();
        service.setLiveOrderDispatcher(dispatcher);
        service.request(1L, liveCredential(), "BTCUSDT", 10, "ISOLATED", approvedLive()).get(3, TimeUnit.SECONDS);
        service.requireReady(1L, "BTCUSDT", 10, "ISOLATED");

        // Simulate an expired background refresh window without waiting in real time.
        var states = (java.util.Map<?, ?>) org.springframework.test.util.ReflectionTestUtils.getField(service, "states");
        Object state = states.values().iterator().next();
        org.springframework.test.util.ReflectionTestUtils.setField(state, "checkedAt", 0L);
        when(gateway.querySymbolTradingSettings(any(), eq("BTCUSDT"))).thenReturn(new org.dromara.quant.exchange.ExchangeAccountPort.SymbolTradingSettings("BTCUSDT", "ISOLATED",
            20));

        service.request(1L, liveCredential(), "BTCUSDT", 10, "ISOLATED", approvedLive()).get(3, TimeUnit.SECONDS);
        assertThrows(OpenCapacityService.CapacityBlockedException.class, () -> service.requireReady(1L, "BTCUSDT", 10, "ISOLATED"));
        verify(gateway, times(1)).setMarginType(any(), eq("BTCUSDT"), eq("ISOLATED"));
        verify(gateway, times(1)).setLeverage(any(), eq("BTCUSDT"), eq(10));
        verify(dispatcher, times(1)).configurationApplied(any());
    }

    @Test
    void missingLiveDispatcherBlocksAllAccountConfigurationWrites() throws Exception {
        ready();
        service.request(1L, liveCredential(), "BTCUSDT", 10, "ISOLATED", approvedLive()).get(3, TimeUnit.SECONDS);
        assertThrows(OpenCapacityService.CapacityBlockedException.class, () -> service.requireReady(1L, "BTCUSDT", 10, "ISOLATED"));
        verify(gateway, never()).setMarginType(any(), any(), any());
        verify(gateway, never()).setLeverage(any(), any(), anyInt());
    }

    @Test
    void timedOutMarginMutationQuarantinesDurableSlotAndNeverChangesLeverage() throws Exception {
        ready();
        var dispatcher = liveDispatcher();
        service.setLiveOrderDispatcher(dispatcher);
        doThrow(new IllegalStateException("Binance timeout")).when(gateway).setMarginType(any(), eq("BTCUSDT"), eq("ISOLATED"));
        service.request(1L, liveCredential(), "BTCUSDT", 10, "ISOLATED", approvedLive()).get(3, TimeUnit.SECONDS);
        verify(dispatcher).unknown(any(LiveOrderDispatcher.Ticket.class));
        verify(dispatcher, never()).configurationApplied(any());
        verify(gateway, never()).setLeverage(any(), any(), anyInt());
    }

    @Test
    void mismatchedBinanceReadbackCannotMarkConfigurationApplied() throws Exception {
        ready();
        when(gateway.querySymbolTradingSettings(any(), eq("BTCUSDT"))).thenReturn(new org.dromara.quant.exchange.ExchangeAccountPort.SymbolTradingSettings("BTCUSDT", "CROSSED",
            20));
        var dispatcher = liveDispatcher();
        service.setLiveOrderDispatcher(dispatcher);
        service.request(1L, liveCredential(), "BTCUSDT", 10, "ISOLATED", approvedLive()).get(3, TimeUnit.SECONDS);
        verify(gateway).setMarginType(any(), eq("BTCUSDT"), eq("ISOLATED"));
        verify(gateway).setLeverage(any(), eq("BTCUSDT"), eq(10));
        verify(dispatcher).unknown(any(LiveOrderDispatcher.Ticket.class));
        verify(dispatcher, never()).configurationApplied(any());
        assertThrows(OpenCapacityService.CapacityBlockedException.class, () -> service.requireReady(1L, "BTCUSDT", 10, "ISOLATED"));
    }

    @Test
    void settingsReadbackOutageAlsoRetainsDurableBlock() throws Exception {
        ready();
        when(gateway.querySymbolTradingSettings(any(), eq("BTCUSDT"))).thenThrow(new IllegalStateException("Binance GET failed"));
        var dispatcher = liveDispatcher();
        service.setLiveOrderDispatcher(dispatcher);
        service.request(1L, liveCredential(), "BTCUSDT", 10, "ISOLATED", approvedLive()).get(3, TimeUnit.SECONDS);
        verify(dispatcher).unknown(any(LiveOrderDispatcher.Ticket.class));
        verify(dispatcher, never()).configurationApplied(any());
    }

    @Test
    void existingOrderDispatchSlotBlocksMarginAndLeverageWritesOnThisNode() throws Exception {
        ready();
        var dispatcher = liveDispatcher();
        service.setLiveOrderDispatcher(dispatcher);
        when(dispatcher.beginConfiguration(eq(1L), eq("BTCUSDT"), anyString(), eq("ISOLATED"), eq(10))).thenThrow(new IllegalStateException("Unknown remote order holds " +
            "account-symbol"));
        service.request(1L, liveCredential(), "BTCUSDT", 10, "ISOLATED", approvedLive()).get(3, TimeUnit.SECONDS);
        verify(gateway, never()).setMarginType(any(), any(), any());
        verify(gateway, never()).setLeverage(any(), any(), anyInt());
    }

    @Test
    void slowWarmupDoesNotBlockEntryReadsAndStopPreventsSettingsChanges() throws Exception {
        ready();
        var started = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        when(gateway.queryPositionMode(any())).thenAnswer(call -> {
            started.countDown();
            assertTrue(release.await(2, TimeUnit.SECONDS));
            return new ExchangeGateway.PositionMode(true);
        });
        var future = service.request(1L, testnet, "BTCUSDT", 10, "ISOLATED", null);
        assertTrue(started.await(1, TimeUnit.SECONDS));
        assertTimeoutPreemptively(java.time.Duration.ofMillis(200), () -> assertThrows(OpenCapacityService.CapacityBlockedException.class, () -> service.requireReady(1L,
            "BTCUSDT", 10, "ISOLATED")));
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var stopped = executor.submit(() -> service.release(1L, "BTCUSDT"));
            var states = (java.util.Map<?, ?>) org.springframework.test.util.ReflectionTestUtils.getField(service, "states");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (!states.isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertTrue(states.isEmpty());
            release.countDown();
            stopped.get(2, TimeUnit.SECONDS);
            future.get(2, TimeUnit.SECONDS);
            verify(gateway, never()).setMarginType(any(), any(), any());
            verify(gateway, never()).setLeverage(any(), any(), anyInt());
            clearInvocations(gateway);
            service.reconcile();
            verifyNoInteractions(gateway);
        } finally {
            release.countDown();
        }
        assertThrows(OpenCapacityService.CapacityBlockedException.class, () -> service.requireReady(1L, "BTCUSDT", 10, "ISOLATED"));
    }

}
