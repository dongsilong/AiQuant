package org.dromara.quant.runtime;

import org.dromara.quant.exchange.ExchangeMarketPort;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StrategyRuntimeRetryDelayTest {
    @Test
    void backsOffRepeatedUnknownOrderChecksWithoutStoppingRecovery() {
        assertEquals(5_000L, StrategyRuntimeService.pendingExecutionRetryDelayMillis(0));
        assertEquals(5_000L, StrategyRuntimeService.pendingExecutionRetryDelayMillis(5));
        assertEquals(10_000L, StrategyRuntimeService.pendingExecutionRetryDelayMillis(6));
        assertEquals(10_000L, StrategyRuntimeService.pendingExecutionRetryDelayMillis(17));
        assertEquals(15_000L, StrategyRuntimeService.pendingExecutionRetryDelayMillis(18));
        assertEquals(15_000L, StrategyRuntimeService.pendingExecutionRetryDelayMillis(35));
        assertEquals(30_000L, StrategyRuntimeService.pendingExecutionRetryDelayMillis(36));
        assertEquals(30_000L, StrategyRuntimeService.pendingExecutionRetryDelayMillis(428));
    }

    @Test
    void preservesSchedulerDeferralWindowWhenWrapped() {
        var deferred = new ExchangeMarketPort.RequestDeferredException(42_000L);
        var wrapped = new IllegalStateException("account snapshot deferred", deferred);

        assertEquals(42_000L, StrategyRuntimeService.exchangeRetryAfterMillis(wrapped));
    }

    @Test
    void preservesExchangeRateLimitWindowWhenWrapped() {
        var limited = new ExchangeMarketPort.RateLimitException("QUERY_ACCOUNT", 429, -1003, "rate limited", 75_000L);
        var wrapped = new IllegalStateException("account snapshot failed", limited);

        assertEquals(75_000L, StrategyRuntimeService.exchangeRetryAfterMillis(wrapped));
    }

    @Test
    void returnsZeroWhenNoExplicitExchangeRetryWindowExists() {
        assertEquals(0L, StrategyRuntimeService.exchangeRetryAfterMillis(new IllegalStateException("network failure")));
    }
}
