package org.dromara.quant.reconcile;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.dromara.quant.credential.domain.QuantCredential;
import org.dromara.quant.credential.mapper.QuantCredentialMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Tag("dev")
class LivePositionRefreshSchedulerTest {
    @Test
    void refreshesAccountsIndependentlyAndWarnsOnRealFailures() throws Exception {
        var credentials = mock(QuantCredentialMapper.class);
        var reconciliation = mock(TradingReconciliationService.class);
        var superseded = new QuantCredential();
        superseded.setId(1L);
        superseded.setRunMode("LIVE");
        var failed = new QuantCredential();
        failed.setId(2L);
        failed.setRunMode("LIVE");
        when(credentials.selectList(any())).thenReturn(List.of(superseded, failed));
        var completed = new CountDownLatch(2);
        doAnswer(invocation -> {
            completed.countDown();
            throw new TradingReconciliationService.SnapshotSupersededException();
        }).when(reconciliation).refreshPositionsOnly(superseded);
        doAnswer(invocation -> {
            completed.countDown();
            throw new IllegalStateException("REST unavailable");
        }).when(reconciliation).refreshPositionsOnly(failed);

        var logger = (Logger) LoggerFactory.getLogger(LivePositionRefreshScheduler.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        var scheduler = new LivePositionRefreshScheduler(credentials, reconciliation);
        try {
            assertDoesNotThrow(scheduler::refresh);
            assertTrue(completed.await(3, TimeUnit.SECONDS), "both account refreshes should finish independently");
            long logDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (System.nanoTime() < logDeadline && !(appender.list.stream()
                .anyMatch(event -> event.getLevel() == Level.WARN && event.getThrowableProxy() != null)
                && appender.list.stream().anyMatch(event -> event.getLevel() == Level.DEBUG
                    && event.getFormattedMessage().contains("已被更新事实取代")))) {
                Thread.sleep(10L);
            }
            assertTrue(appender.list.stream().anyMatch(event -> event.getLevel() == Level.WARN && event.getThrowableProxy() != null));
            assertTrue(appender.list.stream().anyMatch(event -> event.getLevel() == Level.DEBUG
                && event.getFormattedMessage().contains("已被更新事实取代")));
        } finally {
            scheduler.destroy();
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
