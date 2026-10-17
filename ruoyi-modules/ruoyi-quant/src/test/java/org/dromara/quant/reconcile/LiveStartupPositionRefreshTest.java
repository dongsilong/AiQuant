package org.dromara.quant.reconcile;

import org.dromara.quant.credential.domain.QuantCredential;
import org.dromara.quant.credential.mapper.QuantCredentialMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Tag("dev")
class LiveStartupPositionRefreshTest {
    private final QuantCredentialMapper credentials = mock(QuantCredentialMapper.class);
    private final TradingReconciliationService reconciliation = mock(TradingReconciliationService.class);
    private final LiveStartupPositionRefresh startup = new LiveStartupPositionRefresh(credentials, reconciliation);

    private static QuantCredential credential(long id, String mode) {
        var c = new QuantCredential();
        c.setId(id);
        c.setStatus("0");
        c.setRunMode(mode);
        return c;
    }

    @Test
    void startupRefreshesLiveAndTestnetEvenWhenNoPeriodicSchedulerRuns() {
        var paper = credential(1L, "PAPER");
        var live = credential(2L, "LIVE");
        var testnet = credential(3L, "TESTNET");
        when(credentials.selectList(any())).thenReturn(List.of(paper, live, testnet));
        startup.run(new DefaultApplicationArguments());
        verify(reconciliation).refreshPositionsOnly(live);
        verify(reconciliation).refreshPositionsOnly(testnet);
        verify(reconciliation, never()).refreshPositionsOnly(paper);
    }

    @Test
    void startupRetriesSupersededSnapshotUntilFreshRestCommitSucceeds() {
        var live = credential(2L, "LIVE");
        when(credentials.selectList(any())).thenReturn(List.of(live));
        doThrow(new TradingReconciliationService.SnapshotSupersededException()).doNothing().when(reconciliation).refreshPositionsOnly(live);
        assertDoesNotThrow(() -> startup.run(new DefaultApplicationArguments()));
        verify(reconciliation, times(2)).refreshPositionsOnly(live);
    }

    @Test
    void startupStillFailsClosedAfterRepeatedAccountEventInvalidation() {
        var live = credential(2L, "LIVE");
        when(credentials.selectList(any())).thenReturn(List.of(live));
        doThrow(new TradingReconciliationService.SnapshotSupersededException()).when(reconciliation).refreshPositionsOnly(live);
        assertThrows(IllegalStateException.class, () -> startup.run(new DefaultApplicationArguments()));
        verify(reconciliation, times(5)).refreshPositionsOnly(live);
    }

    @Test
    void startupFailsClosedIfAnyAuthenticatedSnapshotFails() {
        var live = credential(2L, "LIVE");
        when(credentials.selectList(any())).thenReturn(List.of(live));
        doThrow(new IllegalStateException("REST unavailable")).when(reconciliation).refreshPositionsOnly(live);
        assertThrows(IllegalStateException.class, () -> startup.run(new DefaultApplicationArguments()));
    }
}
