package org.dromara.quant.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for recovery after a CONNECTED event was ignored while the
 * strategy lease was temporarily fenced.
 */
class StrategyMarketHealthRecoveryTest {

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
