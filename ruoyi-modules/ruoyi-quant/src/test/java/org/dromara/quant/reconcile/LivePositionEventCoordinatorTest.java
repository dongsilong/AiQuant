package org.dromara.quant.reconcile;

import org.dromara.quant.credential.domain.QuantCredential;
import org.dromara.quant.credential.mapper.QuantCredentialMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import java.util.List;

@Tag("dev")
class LivePositionEventCoordinatorTest {
    @Test
    void duplicateEventsCoalesceToOneRestPositionRefresh() {
        var credentials = mock(QuantCredentialMapper.class);
        var reconciliation = mock(TradingReconciliationService.class);
        var coordinator = new LivePositionEventCoordinator(credentials, reconciliation);
        QuantCredential live = new QuantCredential();
        live.setId(42L);
        live.setStatus("0");
        live.setRunMode("LIVE");
        when(credentials.selectById(42L)).thenReturn(live);
        for (int i=0; i<100; i++) coordinator.request(42L);
        coordinator.drain();
        coordinator.drain();
        verify(reconciliation,times(1)).refreshPositionsOnly(live);
    }

    @Test
    void nonLiveAccountsNeverTriggerSignedRestPositionCalls() {
        var credentials = mock(QuantCredentialMapper.class);
        var reconciliation = mock(TradingReconciliationService.class);
        var coordinator = new LivePositionEventCoordinator(credentials, reconciliation);
        QuantCredential paper = new QuantCredential();
        paper.setId(8L); paper.setStatus("0"); paper.setRunMode("PAPER");
        when(credentials.selectById(8L)).thenReturn(paper);
        coordinator.request(8L);
        coordinator.drain();
        verifyNoInteractions(reconciliation);
    }
    @Test
    void failedRestRefreshIsRetriedRatherThanLost() {
        var credentials = mock(QuantCredentialMapper.class);
        var reconciliation = mock(TradingReconciliationService.class);
        var coordinator = new LivePositionEventCoordinator(credentials, reconciliation);
        QuantCredential live = new QuantCredential();
        live.setId(43L); live.setStatus("0"); live.setRunMode("LIVE");
        when(credentials.selectById(43L)).thenReturn(live);
        doThrow(new IllegalStateException("network failure"))
            .when(reconciliation).refreshPositionsOnly(live);
        coordinator.request(43L);
        coordinator.drain();
        verify(reconciliation, times(1)).refreshPositionsOnly(live);
        // No immediate hot-loop retry; the failed request stays queued.
        coordinator.drain();
        verify(reconciliation, times(1)).refreshPositionsOnly(live);
    }

}
