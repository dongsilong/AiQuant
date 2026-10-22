package org.dromara.quant.runtime;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StrategyPositionReturnRateTest {
    @Test
    void calculatesLongAndShortRatesOnCurrentMarkedNotionalMargin() {
        assertEquals(new BigDecimal("11.5942594200"), StrategyPositionReturnRate.calculate(
            "LONG", new BigDecimal("38"), new BigDecimal("0.5998212"), new BigDecimal("0.61406035"), 5));
        assertEquals(new BigDecimal("11.5942594200"), StrategyPositionReturnRate.calculate(
            "SHORT", new BigDecimal("-38"), new BigDecimal("0.6282995"), new BigDecimal("0.61406035"), 5));
    }

    @Test
    void ignoresInvalidMarkOrLeverage() {
        assertEquals(null, StrategyPositionReturnRate.calculate("LONG", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, 5));
        assertEquals(null, StrategyPositionReturnRate.calculate("LONG", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, 0));
    }
}
