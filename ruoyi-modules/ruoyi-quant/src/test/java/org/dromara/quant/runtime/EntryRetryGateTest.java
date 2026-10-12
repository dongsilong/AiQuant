package org.dromara.quant.runtime;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@Tag("dev")
class EntryRetryGateTest {
    @Test
    void unchangedCapacityNeverRetriesButReleasedCapacityAllowsEntry() {
        var gate = new EntryRetryGate();
        gate.failed(0, true);
        assertFalse(gate.allow(4999, () -> fail("must not query during cooldown")));
        assertThrows(IllegalStateException.class, () -> gate.allow(5000, () -> { throw new IllegalStateException("occupied"); }));
        assertThrows(IllegalStateException.class, () -> gate.allow(10000, () -> { throw new IllegalStateException("occupied"); }));
        assertTrue(gate.allow(15000, () -> {}));
        assertTrue(gate.allow(15001, () -> fail("healthy gate needs no snapshot")));
    }

    @Test
    void transientFailuresBackOffAndSuccessfulExecutionResets() {
        var gate = new EntryRetryGate();
        long now = 0;
        for (long delay : new long[]{1000, 2000, 5000, 10000, 30000, 30000}) {
            gate.failed(now, false);
            assertFalse(gate.allow(now + delay - 1, () -> {}));
            now += delay;
            assertTrue(gate.allow(now, () -> {}));
        }
        gate.reset();
        assertTrue(gate.allow(now, () -> fail("reset requires no snapshot")));
        gate.failed(now, false);
        assertTrue(gate.allow(now + 1000, () -> {}));
    }

    @Test
    void unreadableCapacityCannotAuthorizeRetry() {
        var gate = new EntryRetryGate();
        gate.failed(0, true);
        assertThrows(IllegalStateException.class, () -> gate.allow(5000, () -> { throw new IllegalStateException("unavailable"); }));
        assertFalse(gate.allow(5001, () -> fail("failed check must cool down")));
        assertThrows(IllegalStateException.class, () -> gate.allow(10000, () -> { throw new IllegalStateException("occupied"); }));
    }
}
