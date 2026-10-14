package org.dromara.quant.reconcile;

import org.dromara.quant.credential.domain.QuantCredential;
import org.dromara.quant.credential.mapper.QuantCredentialMapper;
import org.dromara.quant.market.UserDataStreamHub;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class LivePositionStreamSingleNodeGateTest {
    @Test
    void existingHubHoldsOnlyOneAccountReferenceAcrossRepeatedSupervision() throws Exception {
        var credentials = mock(QuantCredentialMapper.class);
        var hub = mock(UserDataStreamHub.class);
        var coordinator = mock(LivePositionEventCoordinator.class);
        var freshness = new LivePositionFreshness();
        var stream = new LivePositionStreamTriggers(credentials, hub, coordinator, freshness);
        var account = new QuantCredential();
        account.setId(71L);
        account.setStatus("0");
        account.setRunMode("LIVE");
        when(credentials.selectList(any())).thenReturn(List.of(account));
        var subscription = mock(AutoCloseable.class);
        when(hub.subscribe(eq(account), any())).thenReturn(subscription);
        stream.supervise();
        stream.supervise();
        verify(hub, times(1)).subscribe(eq(account), any());
        verify(coordinator).request(71L);
        stream.close();
        verify(subscription).close();
    }

    @Test
    void disabledAccountReleasesHubSubscription() throws Exception {
        var credentials = mock(QuantCredentialMapper.class);
        var hub = mock(UserDataStreamHub.class);
        var coordinator = mock(LivePositionEventCoordinator.class);
        var stream = new LivePositionStreamTriggers(credentials, hub, coordinator, new LivePositionFreshness());
        var account = new QuantCredential();
        account.setId(72L);
        account.setStatus("0");
        account.setRunMode("LIVE");
        when(credentials.selectList(any())).thenReturn(List.of(account), List.of());
        var subscription = mock(AutoCloseable.class);
        when(hub.subscribe(eq(account), any())).thenReturn(subscription);
        stream.supervise();
        stream.supervise();
        verify(subscription).close();
    }
}
