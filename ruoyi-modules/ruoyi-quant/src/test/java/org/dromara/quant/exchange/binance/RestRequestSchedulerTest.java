package org.dromara.quant.exchange.binance;

import org.dromara.quant.exchange.ExchangeMarketPort;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestRequestSchedulerTest {
    @Test
    void globallyDefersRequestsUntilRateLimitCooldownExpires() {
        RestRequestScheduler scheduler = new RestRequestScheduler(1);
        scheduler.deferFor(5_000);

        ExchangeMarketPort.RequestDeferredException deferred = assertThrows(ExchangeMarketPort.RequestDeferredException.class,
            () -> scheduler.acquire(RestRequestScheduler.Kind.BACKGROUND));

        assertTrue(deferred.retryAfterMillis() > 0);
        assertTrue(deferred.retryAfterMillis() <= 5_000);
    }
}
