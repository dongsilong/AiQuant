package org.dromara.quant.market;

import org.dromara.quant.exchange.ExchangeGateway;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketEventFreshnessTest {
    @Test
    void staleLiveEventRemainsDistinctFromReplayAndSurvivesMaEnrichment() {
        ExchangeGateway.MarketEvent event = new ExchangeGateway.MarketEvent(
            "MARK", "BTCUSDT", "1m", null, null, Instant.parse("2026-11-28T06:55:00Z"),
            null, Instant.parse("2026-11-28T06:55:00Z"), false
        ).withStale(true);

        ExchangeGateway.MarketEvent enriched = event.withOneMinuteMa(null, null, null, false);

        assertTrue(enriched.stale());
        assertFalse(enriched.replay());
    }

    @Test
    void acceptsRecentExchangeEventWhenLocalClockIsThreeSecondsAhead() {
        Instant localNow = Instant.parse("2026-10-17T07:21:33Z");
        Instant eventTime = localNow.minusMillis(3_100);
        Instant receivedAt = localNow.minusMillis(40);

        assertTrue(MarketEventFreshness.isFresh(eventTime, receivedAt, localNow, localNow.minusMillis(2_979), 3_000));
    }

    @Test
    void rejectsEventDelayedInLocalProcessingQueueEvenWhenExchangeClockIsAdjusted() {
        Instant localNow = Instant.parse("2026-10-17T07:21:33Z");
        Instant eventTime = localNow.minusMillis(3_100);
        Instant receivedAt = localNow.minusMillis(3_100);

        assertFalse(MarketEventFreshness.isFresh(eventTime, receivedAt, localNow, localNow.minusMillis(2_979), 3_000));
    }

    @Test
    void rejectsExchangeEventThatIsActuallyStaleAfterClockAdjustment() {
        Instant localNow = Instant.parse("2026-10-17T07:21:33Z");
        Instant eventTime = localNow.minusMillis(6_500);
        Instant receivedAt = localNow.minusMillis(40);

        assertFalse(MarketEventFreshness.isFresh(eventTime, receivedAt, localNow, localNow.minusMillis(2_979), 3_000));
    }

    @Test
    void reportsDistinctFreshnessRejectionReasons() {
        Instant localNow = Instant.parse("2026-10-17T07:21:33Z");
        Instant exchangeNow = localNow.minusMillis(2_979);

        assertEquals("EVENT_TIME_MISSING", MarketEventFreshness.rejectionReason(null, localNow, localNow, exchangeNow, 3_000));
        assertEquals("RECEIVED_AT_MISSING", MarketEventFreshness.rejectionReason(localNow, null, localNow, exchangeNow, 3_000));
        assertEquals("EXCHANGE_EVENT_STALE", MarketEventFreshness.rejectionReason(localNow.minusMillis(6_500), localNow, localNow, exchangeNow, 3_000));
        assertEquals("EXCHANGE_TIME_FUTURE", MarketEventFreshness.rejectionReason(localNow.plusSeconds(2), localNow, localNow, exchangeNow, 3_000));
        assertEquals("LOCAL_RECEIVE_STALE", MarketEventFreshness.rejectionReason(localNow.minusMillis(3_100), localNow.minusMillis(3_100), localNow, exchangeNow, 3_000));
        assertEquals(null, MarketEventFreshness.rejectionReason(localNow.minusMillis(3_100), localNow.minusMillis(40), localNow, exchangeNow, 3_000));
    }

    @Test
    void acceptsKlineEventAtPeriodOpenAndBeforeNextPeriod() {
        Instant open = Instant.parse("2026-11-08T00:45:00Z");
        Instant nextOpen = Instant.parse("2026-11-08T01:00:00Z");

        assertTrue(MarketStreamHub.isEventTimeWithinKlinePeriod(open, open, nextOpen));
        assertTrue(MarketStreamHub.isEventTimeWithinKlinePeriod(nextOpen.minusMillis(1), open, nextOpen));
    }

    @Test
    void rejectsKlineEventAtOrOutsidePeriodBoundaries() {
        Instant open = Instant.parse("2026-11-08T00:45:00Z");
        Instant nextOpen = Instant.parse("2026-11-08T01:00:00Z");

        assertFalse(MarketStreamHub.isEventTimeWithinKlinePeriod(open.minusMillis(1), open, nextOpen));
        assertFalse(MarketStreamHub.isEventTimeWithinKlinePeriod(nextOpen, open, nextOpen));
    }

    @Test
    void acceptsTradeOnlyInsideImmediatelyFollowingKlinePeriod() {
        Instant nextOpen = Instant.parse("2026-11-08T00:45:00Z");
        Instant nextPeriodOpen = Instant.parse("2026-11-08T01:00:00Z");

        assertTrue(MarketStreamHub.isTradeInNextKlinePeriod(nextOpen, nextOpen, nextPeriodOpen));
        assertTrue(MarketStreamHub.isTradeInNextKlinePeriod(nextPeriodOpen.minusMillis(1), nextOpen, nextPeriodOpen));
        assertFalse(MarketStreamHub.isTradeInNextKlinePeriod(nextOpen.minusMillis(1), nextOpen, nextPeriodOpen));
        assertFalse(MarketStreamHub.isTradeInNextKlinePeriod(nextPeriodOpen, nextOpen, nextPeriodOpen));
    }

    @Test
    void rejectsExchangeTimestampMoreThanOneSecondInTheFuture() {
        Instant localNow = Instant.parse("2026-10-17T07:21:33Z");

        assertFalse(MarketEventFreshness.isExchangeTimeFresh(localNow.plusSeconds(2), localNow, 3_000));
    }
}
