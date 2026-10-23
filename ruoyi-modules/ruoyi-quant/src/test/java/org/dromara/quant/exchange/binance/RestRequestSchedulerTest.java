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

    @Test
    void manualBlockDefersEveryRequestClassUntilProcessRestart() {
        RestRequestScheduler scheduler = new RestRequestScheduler(1);
        scheduler.blockUntilManualReset();

        for (RestRequestScheduler.Kind kind : RestRequestScheduler.Kind.values()) {
            ExchangeMarketPort.RequestDeferredException deferred = assertThrows(ExchangeMarketPort.RequestDeferredException.class,
                () -> scheduler.acquire(kind), "manual block must be shared across " + kind);
            assertTrue(deferred.retryAfterMillis() > 0);
        }

        // A later finite cooldown must not clear the manual latch.
        scheduler.deferFor(1);
        assertThrows(ExchangeMarketPort.RequestDeferredException.class,
            () -> scheduler.acquire(RestRequestScheduler.Kind.TRADE));
    }

    @Test
    void cooldownAppliesAcrossTradeAccountAndBackgroundQueues() {
        RestRequestScheduler scheduler = new RestRequestScheduler(1);
        scheduler.deferFor(5_000);

        for (RestRequestScheduler.Kind kind : RestRequestScheduler.Kind.values()) {
            ExchangeMarketPort.RequestDeferredException deferred = assertThrows(ExchangeMarketPort.RequestDeferredException.class,
                () -> scheduler.acquire(kind), "cooldown must be shared across " + kind);
            assertTrue(deferred.retryAfterMillis() > 0);
        }
    }
}
