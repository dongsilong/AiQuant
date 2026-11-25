package org.dromara.quant.strategy;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StrategyDescriptorTest {

    @Test
    void legacyConstructorsKeepEmptyParameterSchema() {
        StrategyDescriptor withoutExample = new StrategyDescriptor("legacy", "1.0", "旧策略");
        StrategyDescriptor withExample = new StrategyDescriptor("legacy", "1.0", "旧策略", Map.of("threshold", 10));

        assertTrue(withoutExample.parameters().isEmpty());
        assertEquals(Map.of(), withoutExample.paramsExample());
        assertTrue(withExample.parameters().isEmpty());
        assertEquals(Map.of("threshold", 10), withExample.paramsExample());
    }
}
