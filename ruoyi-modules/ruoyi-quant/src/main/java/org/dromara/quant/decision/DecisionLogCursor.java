package org.dromara.quant.decision;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 由单个实例的行情锁保护；原因变化/换周期/重新达标产生新片段。 */
public final class DecisionLogCursor {
    private Instant bar;
    private String reason;
    private String key;

    public String key(Instant nextBar, String nextReason) {
        if (key == null || !Objects.equals(bar, nextBar) || !Objects.equals(reason, nextReason)) {
            bar = nextBar;
            reason = nextReason;
            key = "E:" + UUID.randomUUID();
        }
        return key;
    }

    public void reset() {
        key = null;
    }
}
