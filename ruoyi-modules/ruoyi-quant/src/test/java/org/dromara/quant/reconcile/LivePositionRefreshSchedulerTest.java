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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class LivePositionRefreshSchedulerTest {
    @Test
    void supersededSnapshotIsExpectedButRealFailuresStillWarn() {
        var credentials = mock(QuantCredentialMapper.class);
        var reconciliation = mock(TradingReconciliationService.class);
        var live = new QuantCredential();
        live.setId(1L);
        live.setRunMode("LIVE");
        when(credentials.selectList(any())).thenReturn(List.of(live));
        doThrow(new TradingReconciliationService.SnapshotSupersededException())
            .doThrow(new IllegalStateException("REST unavailable")).when(reconciliation).refreshPositionsOnly(live);
        var logger = (Logger) LoggerFactory.getLogger(LivePositionRefreshScheduler.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var scheduler = new LivePositionRefreshScheduler(credentials, reconciliation);
            assertDoesNotThrow(scheduler::refresh);
            assertTrue(appender.list.stream().noneMatch(event -> event.getLevel().isGreaterOrEqual(Level.WARN)));
            assertDoesNotThrow(scheduler::refresh);
            assertTrue(appender.list.stream().anyMatch(event -> event.getLevel() == Level.WARN && event.getThrowableProxy() != null));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
