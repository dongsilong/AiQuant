package org.dromara.quant.reconcile;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TradingReconciliationSchedulerTest {

    @Test
    void reconcileMustRemainScheduled() throws NoSuchMethodException {
        Method reconcile = TradingReconciliationScheduler.class.getMethod("reconcile");

        Scheduled scheduled = reconcile.getAnnotation(Scheduled.class);

        assertNotNull(scheduled, "交易事实对账必须保持定时触发，否则成交不会自动写入量化成交账本");
        assertEquals("#{@quantProperties.reconcile.intervalSeconds.toString()}", scheduled.fixedDelayString());
        assertEquals("15", scheduled.initialDelayString());
        assertEquals(TimeUnit.SECONDS, scheduled.timeUnit());
    }

    @Test
    void deferredRetriesUseTheServerDelayAndBoundedExponentialBackoff() {
        assertEquals(2_000L, TradingReconciliationScheduler.retryDelayMillis(2_000L, 1));
        assertEquals(4_000L, TradingReconciliationScheduler.retryDelayMillis(0L, 3));
        assertEquals(30_000L, TradingReconciliationScheduler.retryDelayMillis(0L, 10));
    }
}
