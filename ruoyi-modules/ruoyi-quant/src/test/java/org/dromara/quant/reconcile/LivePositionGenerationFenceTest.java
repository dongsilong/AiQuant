package org.dromara.quant.reconcile;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("dev")
class LivePositionGenerationFenceTest {
    @Test
    void liveEntryGateRequiresConnectedPrivateStreamAndFreshRestSnapshot() {
        var freshness = new LivePositionFreshness();
        freshness.requirePrivateStream(41L, "TEST_SUBSCRIPTION");
        long generation = freshness.generation(41L);

        assertFalse(freshness.verifiedIfUnchanged(41L, generation));
        freshness.verified(41L);
        assertFalse(freshness.fresh(41L));

        freshness.privateStreamConnected(41L);
        assertFalse(freshness.fresh(41L), "WebSocket connection alone is not a position verification");
        assertTrue(freshness.verifiedIfUnchanged(41L, generation));
        assertTrue(freshness.fresh(41L));

        freshness.privateStreamDisconnected(41L, "TEST_DISCONNECT");
        assertFalse(freshness.fresh(41L));
        assertFalse(freshness.verifiedIfUnchanged(41L, freshness.generation(41L)));
    }

    @Test
    void clearingLiveStreamRequirementAllowsNonLiveSnapshotVerification() {
        var freshness = new LivePositionFreshness();
        freshness.requirePrivateStream(42L, "TEST_SUBSCRIPTION");
        freshness.clearPrivateStreamRequirement(42L, "RUN_MODE_NOT_LIVE");
        assertTrue(freshness.verifiedIfUnchanged(42L, freshness.generation(42L)));
        assertTrue(freshness.fresh(42L));
    }

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
