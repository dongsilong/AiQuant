package org.dromara.quant.runtime.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("dev")
class DualHedgeLocalProtectionServiceTest {
    @Test
    void localProtectionActionNamesArePersistable() {
        assertEquals("FIRST_TAKE_PROFIT",
            org.dromara.quant.runtime.DualHedgeLocalProtectionEvaluator.Action.FIRST_TAKE_PROFIT.name());
        assertEquals("TRAILING_STOP",
            org.dromara.quant.runtime.DualHedgeLocalProtectionEvaluator.Action.TRAILING_STOP.name());
    }
}
