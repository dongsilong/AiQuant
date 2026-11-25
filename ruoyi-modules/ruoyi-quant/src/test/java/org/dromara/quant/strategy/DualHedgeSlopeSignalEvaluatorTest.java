package org.dromara.quant.strategy;

import org.dromara.quant.exchange.ExchangeGateway;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DualHedgeSlopeSignalEvaluatorTest {
    private static final Instant OPEN_TIME = Instant.parse("2026-11-25T00:00:00Z");

    @Test
    void positiveAngleTriggersBothLongAndShort() {
        var signal = DualHedgeSlopeSignalEvaluator.evaluate("15m", kline("102", true),
            new BigDecimal("100"), Map.of()).orElseThrow();

        assertTrue(signal.openLong());
        assertTrue(signal.openShort());
        assertEquals(OPEN_TIME, signal.klineOpenTime());
        assertEquals(new BigDecimal("45"), signal.entryThresholdDegrees());
        assertTrue(signal.angleDegrees().signum() > 0);
    }

    @Test
    void negativeAngleAlsoTriggersBothLongAndShort() {
        var signal = DualHedgeSlopeSignalEvaluator.evaluate("15m", kline("98", true),
            new BigDecimal("100"), Map.of()).orElseThrow();

        assertTrue(signal.openLong());
        assertTrue(signal.openShort());
        assertTrue(signal.angleDegrees().signum() < 0);
    }

    @Test
    void angleEqualToThresholdDoesNotTrigger() {
        assertTrue(DualHedgeSlopeSignalEvaluator.evaluate("15m", kline("101", true),
            new BigDecimal("100"), Map.of()).isEmpty());
    }

    @Test
    void ignoresOtherIntervalsAndUnclosedCandles() {
        assertTrue(DualHedgeSlopeSignalEvaluator.evaluate("1m", kline("102", true),
            new BigDecimal("100"), Map.of()).isEmpty());
        assertTrue(DualHedgeSlopeSignalEvaluator.evaluate("15m", kline("102", false),
            new BigDecimal("100"), Map.of()).isEmpty());
    }

    @Test
    void ignoresMissingOrInvalidReferencePrice() {
        assertTrue(DualHedgeSlopeSignalEvaluator.evaluate("15m", kline("102", true), null, Map.of()).isEmpty());
        assertTrue(DualHedgeSlopeSignalEvaluator.evaluate("15m", kline("102", true),
            BigDecimal.ZERO, Map.of()).isEmpty());
    }

    @Test
    void readsConfiguredThresholdAndMarginRatio() {
        assertEquals(new BigDecimal("50"), DualHedgeSlopeSignalEvaluator.entryAngleDegrees(
            Map.of("entryAngleDegrees", "50")));
        assertEquals(new BigDecimal("0.15"), DualHedgeSlopeSignalEvaluator.marginRatio(
            Map.of("marginRatio", "0.15")));
        assertEquals(new BigDecimal("0.10"), DualHedgeSlopeSignalEvaluator.marginRatio(Map.of()));
    }

    @Test
    void rejectsInvalidThresholdAndMarginRatio() {
        assertThrows(IllegalArgumentException.class,
            () -> DualHedgeSlopeSignalEvaluator.entryAngleDegrees(Map.of("entryAngleDegrees", "90")));
        assertThrows(IllegalArgumentException.class,
            () -> DualHedgeSlopeSignalEvaluator.marginRatio(Map.of("marginRatio", "1")));
    }

    private static ExchangeGateway.Kline kline(String close, boolean closed) {
        return new ExchangeGateway.Kline(OPEN_TIME, OPEN_TIME.plusSeconds(899),
            new BigDecimal("100"), new BigDecimal("103"), new BigDecimal("97"),
            new BigDecimal(close), BigDecimal.ONE, closed);
    }
}
