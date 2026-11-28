package org.dromara.quant.runtime;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DualHedgeLocalProtectionEvaluatorTest {
    @Test
    void hardStopIsThreePercentOfLeveragedReturnForBothLegs() {
        var longDecision = DualHedgeLocalProtectionEvaluator.evaluate(
            "LONG", bd("97"), bd("100"), 1, bd("0"), false);
        var shortDecision = DualHedgeLocalProtectionEvaluator.evaluate(
            "SHORT", bd("103"), bd("100"), 1, bd("0"), false);

        assertEquals(DualHedgeLocalProtectionEvaluator.Action.HARD_STOP, longDecision.action());
        assertEquals(DualHedgeLocalProtectionEvaluator.Action.HARD_STOP, shortDecision.action());
    }

    @Test
    void firstProfitClosesHalfAndNoFirstProfitReturningToEntryClosesAll() {
        var takeProfit = DualHedgeLocalProtectionEvaluator.evaluate(
            "LONG", bd("105"), bd("100"), 1, bd("0.04"), false);
        var breakEven = DualHedgeLocalProtectionEvaluator.evaluate(
            "SHORT", bd("100"), bd("100"), 10, bd("0.049"), false);

        assertEquals(DualHedgeLocalProtectionEvaluator.Action.FIRST_TAKE_PROFIT, takeProfit.action());
        assertEquals(DualHedgeLocalProtectionEvaluator.Action.RETURN_TO_ENTRY, breakEven.action());
    }

    @Test
    void trailingLocksFiveTenTwentyAndThirtyPercentAtTenTwentyThirtyAndForty() {
        assertLock("110", "105", "0.05", 1);
        assertLock("120", "110", "0.10", 2);
        assertLock("130", "120", "0.20", 3);
        assertLock("140", "130", "0.30", 4);
    }

    @Test
    void doesNotRegressPreviouslyObservedMaximumReturn() {
        var decision = DualHedgeLocalProtectionEvaluator.evaluate(
            "LONG", bd("110"), bd("100"), 1, bd("0.20"), true);

        assertEquals(bd("0.20"), decision.maximumRoi());
        assertEquals(bd("0.10"), decision.lockRoi());
        assertEquals(DualHedgeLocalProtectionEvaluator.Action.TRAILING_STOP, decision.action());
    }

    @Test
    void rejectsInvalidPriceOrDirection() {
        assertThrows(IllegalArgumentException.class, () ->
            DualHedgeLocalProtectionEvaluator.evaluate("BOTH", bd("100"), bd("100"), 10, bd("0"), false));
    }

    private static void assertLock(String peakPrice, String currentPrice, String expectedLock, int expectedStep) {
        var decision = DualHedgeLocalProtectionEvaluator.evaluate(
            "LONG", bd(currentPrice), bd("100"), 1, bd(peakPrice).subtract(bd("100")).divide(bd("100")), true);
        assertEquals(bd(expectedLock), decision.lockRoi());
        assertEquals(expectedStep, decision.stepIndex());
        assertEquals(DualHedgeLocalProtectionEvaluator.Action.TRAILING_STOP, decision.action());
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
