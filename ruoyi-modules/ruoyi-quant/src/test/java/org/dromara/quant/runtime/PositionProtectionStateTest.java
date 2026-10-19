package org.dromara.quant.runtime;

import org.dromara.quant.runtime.domain.QuantStrategyPositionState;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.Tag("dev")
class PositionProtectionStateTest {
    @Test
    void newCycleResetsPreviousProfitAndPendingFacts() {
        var s = new QuantStrategyPositionState();
        s.setStatus("CLOSED");
        s.setPositionCycleKey("previous");
        s.setPositionId(5L);
        s.setDirection("LONG");
        s.setEntryPrice(new BigDecimal("90"));
        s.setEntryAtr(new BigDecimal("2"));
        s.setPositionQty(BigDecimal.ZERO);
        s.setInitialPositionQty(BigDecimal.TEN);
        s.setInitialMargin(new BigDecimal("100"));
        s.setFrozenStopLossRatio(new BigDecimal("0.05"));
        s.setStrategyParamsSnapshot("{\"old\":true}");
        s.setFirstTakeProfitTriggered(true);
        s.setFirstTakeProfitTargetQty(new BigDecimal("5"));
        s.setHighestStep(8);
        s.setConfirmedProtectionStep(7);
        s.setPendingAction("OPEN");
        s.setPendingIdempotencyKey("old-key");
        s.setPendingOrderId(5L);
        s.setPendingClientOrderId("old-client");
        s.setPendingOrderTime(100L);
        s.setPendingNotFoundConfirmations(3);
        s.setRecoveryRequired(true);
        s.setUnsafeRecovery(true);
        s.setFirstTakeProfitExecutedQty(BigDecimal.TEN);
        s.setFirstTakeProfitLocalExecutedQty(BigDecimal.TEN);
        s.setHighestPriceSinceEntry(new BigDecimal("999"));
        PositionProtectionState.startCycle(s, 20, new BigDecimal("0.12"), new BigDecimal("100"));
        assertNotEquals("previous", s.getPositionCycleKey());
        assertNull(s.getPositionId());
        assertNull(s.getDirection());
        assertNull(s.getEntryAtr());
        assertEquals(BigDecimal.ZERO, s.getPositionQty());
        assertEquals(BigDecimal.ZERO, s.getInitialPositionQty());
        assertEquals(BigDecimal.ZERO, s.getInitialMargin());
        assertNull(s.getFrozenStopLossRatio());
        assertNull(s.getStrategyParamsSnapshot());
        assertFalse(s.getFirstTakeProfitTriggered());
        assertEquals(0, s.getHighestStep());
        assertEquals(0, s.getConfirmedProtectionStep());
        assertNull(s.getFirstTakeProfitTargetQty());
        assertNull(s.getPendingAction());
        assertNull(s.getPendingIdempotencyKey());
        assertNull(s.getPendingOrderId());
        assertNull(s.getPendingClientOrderId());
        assertNull(s.getPendingOrderTime());
        assertEquals(0, s.getPendingNotFoundConfirmations());
        assertFalse(s.getRecoveryRequired());
        assertFalse(s.getUnsafeRecovery());
        assertEquals(20, s.getFrozenLeverage());
        assertEquals(new BigDecimal("100"), s.getHighestPriceSinceEntry());
        assertEquals(new BigDecimal("100"), s.getLowestPriceSinceEntry());
        assertEquals(BigDecimal.ZERO, s.getFirstTakeProfitExecutedQty());
        assertEquals(BigDecimal.ZERO, s.getFirstTakeProfitLocalExecutedQty());
    }

    @Test
    void partialFirstProfitOnlySupplementsRemainingAndRepeatedExchangeSnapshotIsIdempotent() {
        var s = new QuantStrategyPositionState();
        PositionProtectionState.startCycle(s, 10, null, BigDecimal.TEN);
        s.setFirstTakeProfitTargetQty(new BigDecimal("5"));
        PositionProtectionState.confirmExchangeFirstProfit(s, new BigDecimal("2"));
        PositionProtectionState.confirmExchangeFirstProfit(s, new BigDecimal("2"));
        assertFalse(s.getFirstTakeProfitTriggered());
        assertEquals(new BigDecimal("3"), PositionProtectionState.remainingFirstProfit(s));
        PositionProtectionState.confirmLocalFirstProfit(s, new BigDecimal("3"));
        PositionProtectionState.confirmExchangeFirstProfit(s, new BigDecimal("2"));
        assertTrue(s.getFirstTakeProfitTriggered());
        assertEquals(new BigDecimal("5"), s.getFirstTakeProfitExecutedQty());
        assertEquals(BigDecimal.ZERO, PositionProtectionState.remainingFirstProfit(s));
    }
}
