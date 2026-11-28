package org.dromara.quant.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for recovery after a CONNECTED event was ignored while the
 * strategy lease was temporarily fenced.
 */
class StrategyMarketHealthRecoveryTest {

    @Test
    void onlyExpiredTradeAndKlineEventsInvalidateDecisionSlope() {
        assertTrue(StrategyRuntimeService.shouldInvalidateSlopeForExpiredEvent("TRADE"));
        assertTrue(StrategyRuntimeService.shouldInvalidateSlopeForExpiredEvent("KLINE"));
        assertFalse(StrategyRuntimeService.shouldInvalidateSlopeForExpiredEvent("MARK"));
        assertFalse(StrategyRuntimeService.shouldInvalidateSlopeForExpiredEvent("CONNECTED"));
    }

    @Test
    void staleLiveEventsAreRejectedWithoutTreatingThemAsReplay() {
        org.dromara.quant.exchange.ExchangeGateway.MarketEvent staleLive = new org.dromara.quant.exchange.ExchangeGateway.MarketEvent(
            "MARK", "BTCUSDT", "1m", null, null, java.time.Instant.parse("2026-11-28T06:55:00Z"),
            null, java.time.Instant.parse("2026-11-28T06:55:00Z"), false
        ).withStale(true);
        org.dromara.quant.exchange.ExchangeGateway.MarketEvent staleReplay = new org.dromara.quant.exchange.ExchangeGateway.MarketEvent(
            "MARK", "BTCUSDT", "1m", null, null, java.time.Instant.parse("2026-11-28T06:55:00Z"),
            null, java.time.Instant.parse("2026-11-28T06:55:00Z"), true
        ).withStale(true);

        assertTrue(StrategyRuntimeService.isStaleLiveMarketEvent(staleLive));
        assertFalse(StrategyRuntimeService.isStaleLiveMarketEvent(staleReplay));
    }

    @Test
    void mapsStreamStatusEventsToSlopeTelemetryReasons() {
        assertEquals("STREAM_CONNECTING_OR_RECOVERING", StrategyRuntimeService.slopeBlockReasonForStreamStatus("CONNECTING"));
        assertEquals("STREAM_DISCONNECTED", StrategyRuntimeService.slopeBlockReasonForStreamStatus("DISCONNECTED"));
        assertEquals("STREAM_TERMINAL_ERROR", StrategyRuntimeService.slopeBlockReasonForStreamStatus("ERROR"));
        assertEquals("WAITING_FOR_LIVE_MARKET_EVENT", StrategyRuntimeService.slopeBlockReasonForStreamStatus("CONNECTED"));
    }

    @Test
    void restoresHealthOnFreshTradeWhenSharedStreamIsReady() {
        assertTrue(StrategyRuntimeService.shouldRestoreMarketHealth(true, false, "TRADE"));
    }

    @Test
    void restoresHealthOnFreshKlineWhenSharedStreamIsReady() {
        assertTrue(StrategyRuntimeService.shouldRestoreMarketHealth(true, false, "KLINE"));
        assertTrue(StrategyRuntimeService.shouldRestoreMarketHealth(true, false, "kline"));
    }

    @Test
    void doesNotRestoreHealthWhenStreamIsNotReady() {
        assertFalse(StrategyRuntimeService.shouldRestoreMarketHealth(false, false, "TRADE"));
        assertFalse(StrategyRuntimeService.shouldRestoreMarketHealth(false, false, "KLINE"));
    }

    @Test
    void replayAndStatusEventsCannotReopenHealthGate() {
        assertFalse(StrategyRuntimeService.shouldRestoreMarketHealth(true, true, "TRADE"));
        assertFalse(StrategyRuntimeService.shouldRestoreMarketHealth(true, true, "KLINE"));
        assertFalse(StrategyRuntimeService.shouldRestoreMarketHealth(true, false, "CONNECTED"));
        assertFalse(StrategyRuntimeService.shouldRestoreMarketHealth(true, false, "MARK"));
        assertFalse(StrategyRuntimeService.shouldRestoreMarketHealth(true, false, "DISCONNECTED"));
        assertFalse(StrategyRuntimeService.shouldRestoreMarketHealth(true, false, null));
    }
}
