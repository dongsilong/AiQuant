package org.dromara.quant.runtime;

import org.dromara.quant.exchange.ExchangeGateway;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DualHedgeRuntimeServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-25T12:15:00Z");

    @Test
    void acceptsOnlyFreshClosedFifteenMinuteKlinesForTheSubscribedSymbol() {
        assertTrue(DualHedgeRuntimeService.isFreshClosed15mKlineEvent(
            event("BTCUSDT", "15m", true, false, NOW), "BTCUSDT", NOW, NOW, 3_000));
    }

    @Test
    void rejectsReplayUnclosedWrongIntervalAndWrongSymbolEvents() {
        assertFalse(isAccepted(event("BTCUSDT", "15m", true, true, NOW)));
        assertFalse(isAccepted(event("BTCUSDT", "15m", false, false, NOW)));
        assertFalse(isAccepted(event("BTCUSDT", "1m", true, false, NOW)));
        assertFalse(isAccepted(event("ETHUSDT", "15m", true, false, NOW)));
    }

    @Test
    void rejectsStaleEventsBeforeUpdatingTheClosedKlineCursor() {
        assertFalse(isAccepted(event("BTCUSDT", "15m", true, false, NOW.minusSeconds(5))));
    }

    private static boolean isAccepted(ExchangeGateway.MarketEvent event) {
        return DualHedgeRuntimeService.isFreshClosed15mKlineEvent(event, "BTCUSDT", NOW, NOW, 3_000);
    }

    private static ExchangeGateway.MarketEvent event(String symbol, String interval, boolean closed,
                                                       boolean replay, Instant eventTime) {
        ExchangeGateway.Kline kline = new ExchangeGateway.Kline(
            NOW.minusSeconds(900), NOW.minusSeconds(1), new BigDecimal("100"),
            new BigDecimal("102"), new BigDecimal("99"), new BigDecimal("101"),
            BigDecimal.ONE, closed);
        return new ExchangeGateway.MarketEvent("KLINE", symbol, interval, new BigDecimal("101"),
            kline, eventTime, 1L, eventTime, replay);
    }
}
