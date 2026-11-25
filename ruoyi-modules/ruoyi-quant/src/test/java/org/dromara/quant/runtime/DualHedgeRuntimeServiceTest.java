package org.dromara.quant.runtime;

import org.dromara.quant.exchange.ExchangeGateway;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.NavigableMap;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void pendingSignalMustStillBeFreshWhenAccountSnapshotBecomesAvailable() {
        assertTrue(DualHedgeRuntimeService.isPendingSignalFresh(
            NOW.minusMillis(1_000), NOW.minusMillis(900), NOW, NOW, 3_000));
        assertFalse(DualHedgeRuntimeService.isPendingSignalFresh(
            NOW.minusMillis(5_000), NOW.minusMillis(4_900), NOW, NOW, 3_000));
    }

    @Test
    void usesImmediatelyPreviousClosedKlineOpenAsSlopeReference() {
        ExchangeGateway.Kline previous = new ExchangeGateway.Kline(
            NOW.minusSeconds(1_800), NOW.minusSeconds(900).minusMillis(1), new BigDecimal("100"),
            new BigDecimal("103"), new BigDecimal("99"), new BigDecimal("102"),
            BigDecimal.ONE, true);
        ExchangeGateway.Kline current = new ExchangeGateway.Kline(
            NOW.minusSeconds(900), NOW.minusSeconds(1), new BigDecimal("102"),
            new BigDecimal("104"), new BigDecimal("101"), new BigDecimal("103"),
            BigDecimal.ONE, true);
        NavigableMap<Instant, ExchangeGateway.Kline> history = new TreeMap<>();
        history.put(previous.openTime(), previous);
        history.put(current.openTime(), current);

        assertEquals(new BigDecimal("100"),
            DualHedgeRuntimeService.referenceOpenPrice(history, current));
    }

    @Test
    void rejectsMissingUnclosedOrDiscontinuousReferenceKline() {
        ExchangeGateway.Kline current = event("BTCUSDT", "15m", true, false, NOW).kline();
        NavigableMap<Instant, ExchangeGateway.Kline> history = new TreeMap<>();
        assertNull(DualHedgeRuntimeService.referenceOpenPrice(history, current));

        ExchangeGateway.Kline unclosedPrevious = new ExchangeGateway.Kline(
            current.openTime().minusSeconds(900), current.openTime().minusMillis(1),
            new BigDecimal("100"), new BigDecimal("102"), new BigDecimal("99"),
            new BigDecimal("101"), BigDecimal.ONE, false);
        history.put(unclosedPrevious.openTime(), unclosedPrevious);
        assertNull(DualHedgeRuntimeService.referenceOpenPrice(history, current));

        ExchangeGateway.Kline gapPrevious = new ExchangeGateway.Kline(
            current.openTime().minusSeconds(901), current.openTime().minusSeconds(2),
            new BigDecimal("100"), new BigDecimal("102"), new BigDecimal("99"),
            new BigDecimal("101"), BigDecimal.ONE, true);
        history.clear();
        history.put(gapPrevious.openTime(), gapPrevious);
        assertNull(DualHedgeRuntimeService.referenceOpenPrice(history, current));
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
