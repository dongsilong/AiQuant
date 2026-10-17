package org.dromara.quant.execution;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.dromara.quant.execution.StrategyPositionAccounting.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("dev")
class StrategyPositionAccountingTest {
    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    private static Fill f(PositionSide p, Role r, String side, String q, String price, String fee, String pnl) {
        return new Fill(p, r, side, d(q), d(price), d(fee), d(pnl));
    }

    @Test
    void weightedAverageCostPartialCloseAndNewCycle() {
        State s = State.empty();
        s = apply(s, f(PositionSide.LONG, Role.OPEN, "BUY", "1", "100", "0.1", "0"));
        s = apply(s, f(PositionSide.LONG, Role.OPEN, "BUY", "2", "130", "0.2", "0"));
        assertEquals(0, s.costBasis().compareTo(d("360")));
        s = apply(s, f(PositionSide.LONG, Role.CLOSE, "SELL", "1", "140", "0.1", "20"));
        assertEquals(0, s.openQuantity().compareTo(d("2")));
        assertEquals(0, s.costBasis().compareTo(d("240")));
        assertEquals(0, s.realizedPnl().compareTo(d("20")));
        s = apply(s, f(PositionSide.LONG, Role.CLOSE, "SELL", "2", "125", "0.2", "10"));
        assertEquals(0, s.openQuantity().signum());
        assertEquals(0, s.costBasis().signum());
        assertEquals(0, s.commissions().compareTo(d("0.6")));
        assertEquals(1, s.cycle());
        s = apply(s, f(PositionSide.LONG, Role.OPEN, "BUY", "0.5", "90", "0.1", "0"));
        assertEquals(2, s.cycle());
    }

    @Test
    void shortInventoryUsesExplicitRoleNotSignedBuySellAssumption() {
        State s = apply(State.empty(), f(PositionSide.SHORT, Role.OPEN, "SELL", "3", "110", "0.3", "0"));
        s = apply(s, f(PositionSide.SHORT, Role.CLOSE, "BUY", "1", "100", "0.1", "10"));
        assertEquals(0, s.openQuantity().compareTo(d("2")));
        assertEquals(0, s.costBasis().compareTo(d("220")));
        assertEquals(0, s.realizedPnl().compareTo(d("10")));
    }

    @Test
    void impossibleCloseOrWrongSideFailClosed() {
        assertThrows(IllegalStateException.class, () -> apply(State.empty(), f(PositionSide.LONG, Role.CLOSE, "SELL", "1", "100", "0", "2")));
        assertThrows(IllegalArgumentException.class, () -> f(PositionSide.SHORT, Role.OPEN, "BUY", "1", "100", "0", "0"));
        assertThrows(IllegalArgumentException.class, () -> f(PositionSide.LONG, Role.OPEN, "BUY", "1", "100", "-1", "0"));
    }
}
