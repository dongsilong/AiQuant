package org.dromara.quant.reconcile;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@Tag("dev")
class LivePositionGenerationFenceTest {
    @Test
    void newerUserEventCannotBeOverwrittenByEarlierRestResponse() {
        var freshness = new LivePositionFreshness();
        long before = freshness.generation(42L);
        freshness.invalidate(42L);
        assertFalse(freshness.verifiedIfUnchanged(42L, before));
        assertFalse(freshness.fresh(42L));
        long after = freshness.generation(42L);
        assertTrue(freshness.verifiedIfUnchanged(42L, after));
        assertTrue(freshness.fresh(42L));
        freshness.invalidate(42L);
        assertFalse(freshness.fresh(42L));
    }
}
