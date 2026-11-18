package org.dromara.quant.market;

import org.dromara.quant.exchange.ExchangeGateway;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OneMinuteMovingAverageTest {
    @Test
    void calculatesMaFromLastSevenClosedKlinesAndIgnoresCurrentKline() {
        List<ExchangeGateway.Kline> klines = new ArrayList<>();
        for (int value = 1; value <= 7; value++) {
            klines.add(kline(value, true));
        }
        klines.add(kline(100, false));

        OneMinuteMovingAverage.Snapshot snapshot = OneMinuteMovingAverage.calculate(klines);

        assertTrue(snapshot.ready());
        assertEquals(7, snapshot.closedKlines());
        assertEquals(0, new BigDecimal("5").compareTo(snapshot.ma5()));
        assertEquals(0, new BigDecimal("4").compareTo(snapshot.ma7()));
        assertEquals("UP", snapshot.trend());
    }

    @Test
    void doesNotMarkMaReadyWithFewerThanSevenClosedKlines() {
        List<ExchangeGateway.Kline> klines = new ArrayList<>();
        for (int value = 1; value <= 6; value++) {
            klines.add(kline(value, true));
        }

        OneMinuteMovingAverage.Snapshot snapshot = OneMinuteMovingAverage.calculate(klines);

        assertFalse(snapshot.ready());
        assertEquals(6, snapshot.closedKlines());
        assertEquals(null, snapshot.ma5());
        assertEquals(null, snapshot.ma7());
        assertEquals(null, snapshot.trend());
    }

    @Test
    void usesTheSameStrictMaDirectionRuleForLongAndShort() {
        BigDecimal ma5 = new BigDecimal("10");
        BigDecimal ma7 = new BigDecimal("9");

        assertTrue(OneMinuteMovingAverage.directionApproved(true, ma5, ma7));
        assertFalse(OneMinuteMovingAverage.directionApproved(false, ma5, ma7));
        assertFalse(OneMinuteMovingAverage.directionApproved(true, ma7, ma7));
        assertFalse(OneMinuteMovingAverage.directionApproved(false, ma7, ma7));
    }

    private static ExchangeGateway.Kline kline(int close, boolean closed) {
        Instant openTime = Instant.ofEpochSecond(close * 60L);
        return new ExchangeGateway.Kline(openTime, openTime.plusSeconds(59), BigDecimal.ONE,
            BigDecimal.valueOf(close), BigDecimal.ONE, BigDecimal.valueOf(close), BigDecimal.ONE, closed);
    }
}
