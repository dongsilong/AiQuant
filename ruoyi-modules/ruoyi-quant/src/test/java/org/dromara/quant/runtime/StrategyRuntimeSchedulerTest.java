package org.dromara.quant.runtime;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class StrategyRuntimeSchedulerTest {

    @Test
    void supervisesStrategyInstancesOnConfiguredRuntimeInterval() throws NoSuchMethodException {
        Scheduled scheduled = StrategyRuntimeScheduler.class.getDeclaredMethod("supervise").getAnnotation(Scheduled.class);

        assertNotNull(scheduled, "Strategy supervision must be scheduled so RUNNING instances are discovered and started");
        assertEquals("#{@quantProperties.runtime.tickSeconds * 1000}", scheduled.fixedDelayString());
        assertEquals(5_000L, scheduled.initialDelay());
    }
}
