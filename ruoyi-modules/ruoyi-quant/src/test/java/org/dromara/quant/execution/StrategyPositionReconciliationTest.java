package org.dromara.quant.execution;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.dromara.quant.execution.StrategyPositionReconciliation.Quantity;
import static org.dromara.quant.execution.StrategyPositionReconciliation.requireMatching;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("dev")
class StrategyPositionReconciliationTest {
    @Test
    void exactHedgeQtyCanPassOnlyWithCompleteIndependentProofs() {
        var q = new Quantity(new BigDecimal("1.00"), new BigDecimal("2.000"));
        var a = new Quantity(new BigDecimal("1"), new BigDecimal("2"));
        assertDoesNotThrow(() -> requireMatching(q, a, true, true, true));
        assertThrows(IllegalStateException.class, () -> requireMatching(q, a, false, true, true));
        assertThrows(IllegalStateException.class, () -> requireMatching(q, a, true, false, true));
        assertThrows(IllegalStateException.class, () -> requireMatching(q, a, true, true, false));
        assertThrows(IllegalStateException.class, () -> requireMatching(q, new Quantity(BigDecimal.ONE, BigDecimal.ONE), true, true, true));
    }
}
