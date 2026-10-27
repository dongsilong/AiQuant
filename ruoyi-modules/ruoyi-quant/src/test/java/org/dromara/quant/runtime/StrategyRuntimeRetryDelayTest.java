package org.dromara.quant.runtime;

import org.dromara.quant.exchange.ExchangeMarketPort;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StrategyRuntimeRetryDelayTest {
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
