package org.dromara.quant.runtime;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DualHedgeProtectionPriceCalculatorTest {
    @Test
    void calculatesLongAndShortStopsFromFixedInitialMargin() {
        assertEquals(new BigDecimal("99.7"), DualHedgeProtectionPriceCalculator.stopPrice(
            "LONG", new BigDecimal("100"), new BigDecimal("10"), new BigDecimal("100"),
            BigDecimal.ZERO, new BigDecimal("-0.03")));
        assertEquals(new BigDecimal("100.3"), DualHedgeProtectionPriceCalculator.stopPrice(
            "SHORT", new BigDecimal("100"), new BigDecimal("10"), new BigDecimal("100"),
            BigDecimal.ZERO, new BigDecimal("-0.03")));
    }

    @Test
    void includesAccumulatedNetAdjustmentsWithoutReapplyingFeeRules() {
        assertEquals(new BigDecimal("99.6"), DualHedgeProtectionPriceCalculator.stopPrice(
            "LONG", new BigDecimal("100"), new BigDecimal("10"), new BigDecimal("100"),
            new BigDecimal("-1"), new BigDecimal("-0.03")));
    }

    @Test
    void usesRemainingQuantityButKeepsInitialMarginFixed() {
        assertEquals(new BigDecimal("99.4"), DualHedgeProtectionPriceCalculator.stopPrice(
            "LONG", new BigDecimal("100"), new BigDecimal("5"), new BigDecimal("100"),
            BigDecimal.ZERO, new BigDecimal("-0.03")));
    }

    @Test
    void rejectsInvalidInputsAndNonPositiveTriggerPrice() {
        assertThrows(IllegalArgumentException.class, () -> DualHedgeProtectionPriceCalculator.stopPrice(
            "BOTH", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, new BigDecimal("-0.03")));
        assertThrows(IllegalArgumentException.class, () -> DualHedgeProtectionPriceCalculator.stopPrice(
            "LONG", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> DualHedgeProtectionPriceCalculator.stopPrice(
            "LONG", BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("100"), BigDecimal.ZERO, new BigDecimal("-0.03")));
    }
}
