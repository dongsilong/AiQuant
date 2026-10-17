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
        s.setPositionCycleKey("previous");
        s.setPositionId(5L);
        s.setFirstTakeProfitTriggered(true);
        s.setHighestStep(8);
        s.setPendingAction("OPEN");
        s.setPendingOrderId(5L);
        s.setFirstTakeProfitExecutedQty(BigDecimal.TEN);
        s.setHighestPriceSinceEntry(new BigDecimal("999"));
        PositionProtectionState.startCycle(s, 20, new BigDecimal("0.12"), new BigDecimal("100"));
        assertNotEquals("previous", s.getPositionCycleKey());
        assertNull(s.getPositionId());
        assertFalse(s.getFirstTakeProfitTriggered());
        assertEquals(0, s.getHighestStep());
        assertNull(s.getPendingAction());
        assertNull(s.getPendingOrderId());
        assertEquals(20, s.getFrozenLeverage());
        assertEquals(new BigDecimal("100"), s.getHighestPriceSinceEntry());
        assertEquals(BigDecimal.ZERO, s.getFirstTakeProfitExecutedQty());
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
