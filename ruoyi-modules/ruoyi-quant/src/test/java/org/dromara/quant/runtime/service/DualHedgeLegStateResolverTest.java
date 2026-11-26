package org.dromara.quant.runtime.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("dev")
class DualHedgeLegStateResolverTest {

    @Test
    void distinguishesNotDispatchedFromCrashDuringDispatch() {
        assertEquals(DualHedgeLegStateResolver.NOT_DISPATCHED,
            resolve(false, null, null, null, null, BigDecimal.ZERO, true, false));
        assertEquals(DualHedgeLegStateResolver.UNKNOWN,
            resolve(true, null, null, null, null, BigDecimal.ZERO, true, false));
        assertEquals(DualHedgeLegStateResolver.DISPATCHING,
            resolve(true, "PENDING", null, null, null, BigDecimal.ZERO, true, false));
    }

    @Test
    void mapsAcceptedAndUnknownExecutionRecords() {
        assertEquals(DualHedgeLegStateResolver.ACCEPTED,
            resolve(true, "CONFIRMED", null, null, null, BigDecimal.ZERO, true, false));
        assertEquals(DualHedgeLegStateResolver.UNKNOWN,
            resolve(true, "UNKNOWN", null, null, null, BigDecimal.ZERO, true, false));
        assertEquals(DualHedgeLegStateResolver.NOT_DISPATCHED,
            resolve(true, "FAILED", null, null, null, BigDecimal.ZERO, true, false));
    }

    @Test
    void mapsOrderLifecycleWithoutTreatingAcceptanceAsFill() {
        assertEquals(DualHedgeLegStateResolver.ACCEPTED,
            resolve(true, "CONFIRMED", "NEW", BigDecimal.ZERO, new BigDecimal("2"), BigDecimal.ZERO, true, false));
        assertEquals(DualHedgeLegStateResolver.PARTIALLY_FILLED,
            resolve(true, "CONFIRMED", "PARTIALLY_FILLED", new BigDecimal("1"), new BigDecimal("2"), BigDecimal.ONE, true, false));
        assertEquals(DualHedgeLegStateResolver.OPEN,
            resolve(true, "CONFIRMED", "FILLED", new BigDecimal("2"), new BigDecimal("2"), new BigDecimal("2"), true, false));
    }

    @Test
    void filledOrderNeedsFreshPositionEvidenceBeforeOpen() {
        assertEquals(DualHedgeLegStateResolver.FILLED_POSITION_UNVERIFIED,
            resolve(true, "CONFIRMED", "FILLED", new BigDecimal("2"), new BigDecimal("2"), null, false, false));
        assertEquals(DualHedgeLegStateResolver.CONFLICT,
            resolve(true, "CONFIRMED", "FILLED", new BigDecimal("2"), new BigDecimal("2"), BigDecimal.ZERO, true, false));
    }

    @Test
    void preservesPartialFillWhenOrderWasCanceledOrRejected() {
        assertEquals(DualHedgeLegStateResolver.PARTIALLY_FILLED,
            resolve(true, "CONFIRMED", "CANCELED", BigDecimal.ONE, new BigDecimal("2"), BigDecimal.ONE, true, false));
        assertEquals(DualHedgeLegStateResolver.REJECTED,
            resolve(true, "FAILED", "REJECTED", BigDecimal.ZERO, new BigDecimal("2"), BigDecimal.ZERO, true, false));
        assertEquals(DualHedgeLegStateResolver.CANCELED,
            resolve(true, "CONFIRMED", "CANCELED", BigDecimal.ZERO, new BigDecimal("2"), BigDecimal.ZERO, true, false));
    }

    @Test
    void anyIdentityConflictOrUnattributedPositionFailsClosed() {
        assertEquals(DualHedgeLegStateResolver.CONFLICT,
            resolve(true, "CONFIRMED", "NEW", BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO, true, true));
        assertEquals(DualHedgeLegStateResolver.CONFLICT,
            resolve(true, null, null, null, null, BigDecimal.ONE, true, false));
    }

    private static String resolve(boolean dispatchStarted, String executionStatus, String orderStatus,
                                   BigDecimal executedQuantity, BigDecimal originalQuantity,
                                   BigDecimal positionQuantity, boolean positionSnapshotComplete,
                                   boolean identityConflict) {
        return DualHedgeLegStateResolver.resolve(dispatchStarted, executionStatus, orderStatus,
            executedQuantity, originalQuantity, positionQuantity, positionSnapshotComplete, identityConflict);
    }
}
