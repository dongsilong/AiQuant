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
            assertTrue(deferred.retryAfterMillis() >= 86_400_000L, "manual latch must not suggest a short automatic retry");
        }

        // A later finite cooldown must not clear the manual latch.
        scheduler.deferFor(1);
        assertThrows(ExchangeMarketPort.RequestDeferredException.class,
            () -> scheduler.acquire(RestRequestScheduler.Kind.TRADE));
    }

    @Test
    void unknown418ExpiryRequiresManualReset() {
        assertTrue(BinanceGatewayAdapter.retryAfterMillis(418, null, "{\"code\":-1003,\"msg\":\"IP banned\"}") < 0L);
        assertTrue(BinanceGatewayAdapter.retryAfterMillis(418, "not-a-date", null) < 0L);
    }

    @Test
    void rateLimitRetryAfterSecondsAndFallbackAreConservative() {
        assertTrue(BinanceGatewayAdapter.retryAfterMillis(429, "3", null) >= 3_000L);
        assertTrue(BinanceGatewayAdapter.retryAfterMillis(429, null, null) == 60_000L);
    }

    @Test
    void parsesBinanceBanUntilTimestamp() {
        long banUntil = System.currentTimeMillis() + 120_000L;
        long delay = BinanceGatewayAdapter.retryAfterMillis(418, null, "{\"msg\":\"IP banned until " + banUntil + "\"}");
        assertTrue(delay > 0L && delay <= 120_000L, "should honor Binance's ban-until timestamp");
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
