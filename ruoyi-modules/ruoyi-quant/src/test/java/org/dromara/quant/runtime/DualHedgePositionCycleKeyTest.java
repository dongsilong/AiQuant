package org.dromara.quant.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DualHedgePositionCycleKeyTest {
    @Test
    void eachLegHasStableUuidLengthAndDistinctIdentity() {
        String longKey = DualHedgePositionCycleKey.of("round-1", "LONG");
        String shortKey = DualHedgePositionCycleKey.of("round-1", "SHORT");

        assertEquals(36, longKey.length());
        assertEquals(longKey, DualHedgePositionCycleKey.of("round-1", "LONG"));
        assertNotEquals(longKey, shortKey);
        assertNotEquals(longKey, DualHedgePositionCycleKey.of("round-2", "LONG"));
    }

    @Test
    void openIdempotencyKeyResolvesOnlyItsExactLegCycle() {
        String longKey = DualHedgePositionCycleKey.of("round-1", "LONG");

        assertEquals(longKey, DualHedgePositionCycleKey.fromOpenIdempotencyKey("round-1:LONG:OPEN"));
        assertTrue(DualHedgePositionCycleKey.matchesOpenIdempotencyKey("round-1:LONG:OPEN", "LONG", longKey));
        assertFalse(DualHedgePositionCycleKey.matchesOpenIdempotencyKey("round-1:SHORT:OPEN", "LONG", longKey));
        assertFalse(DualHedgePositionCycleKey.matchesOpenIdempotencyKey("round-1:LONG:CLOSE", "LONG", longKey));
    }

    @Test
    void invalidRoundOrLegIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> DualHedgePositionCycleKey.of("", "LONG"));
        assertThrows(IllegalArgumentException.class, () -> DualHedgePositionCycleKey.of("round-1", "BOTH"));
    }
}
