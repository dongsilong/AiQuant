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
        when(hub.hasLocalStream(anyLong())).thenReturn(true);
        var coordinator = mock(LivePositionEventCoordinator.class);
        var freshness = new LivePositionFreshness();
        var lease = mock(org.dromara.quant.reconcile.mapper.LiveUserStreamLeaseMapper.class);
        when(lease.acquireOrRenew(anyLong(), anyString(), anyInt())).thenReturn(1);
        var stream = new LivePositionStreamTriggers(credentials, hub, coordinator, freshness, lease);
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
        verify(hub, times(2)).registerLeaseOwner(eq(71L), anyString());
        stream.close();
        verify(subscription).close();
    }

    @Test
    void disabledAccountReleasesHubSubscription() throws Exception {
        var credentials = mock(QuantCredentialMapper.class);
        var hub = mock(UserDataStreamHub.class);
        when(hub.hasLocalStream(anyLong())).thenReturn(true);
        var coordinator = mock(LivePositionEventCoordinator.class);
        var lease = mock(org.dromara.quant.reconcile.mapper.LiveUserStreamLeaseMapper.class);
        when(lease.acquireOrRenew(anyLong(), anyString(), anyInt())).thenReturn(1);
        var stream = new LivePositionStreamTriggers(credentials, hub, coordinator, new LivePositionFreshness(), lease);
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
        verify(hub).unregisterLeaseOwner(eq(72L), anyString());
    }
    @Test
    void foreignOwnerNeverCreatesSharedStreamAndRevokesPriorReference() throws Exception {
        var credentials = mock(QuantCredentialMapper.class);
        var hub = mock(UserDataStreamHub.class);
        when(hub.hasLocalStream(anyLong())).thenReturn(true);
        var coordinator = mock(LivePositionEventCoordinator.class);
        var lease = mock(org.dromara.quant.reconcile.mapper.LiveUserStreamLeaseMapper.class);
        var freshness = new LivePositionFreshness();
        var stream = new LivePositionStreamTriggers(credentials, hub, coordinator, freshness, lease);
        var account = new QuantCredential();
        account.setId(91L); account.setStatus("0"); account.setRunMode("LIVE");
        when(credentials.selectList(any())).thenReturn(List.of(account));
        when(lease.acquireOrRenew(anyLong(), anyString(), anyInt())).thenReturn(1, 0);
        var reference = mock(AutoCloseable.class);
        when(hub.subscribe(eq(account), any())).thenReturn(reference);
        stream.supervise();
        freshness.verified(91L);
        stream.supervise();
        verify(hub, times(1)).subscribe(eq(account), any());
        verify(reference).close();
        verify(hub).unregisterLeaseOwner(eq(91L), anyString());
        org.junit.jupiter.api.Assertions.assertFalse(freshness.fresh(91L));
    }
    @Test
    void failedHubSubscriptionReleasesDbLeaseImmediately() {
        var credentials = mock(QuantCredentialMapper.class);
        var hub = mock(UserDataStreamHub.class);
        when(hub.hasLocalStream(anyLong())).thenReturn(true);
        var coordinator = mock(LivePositionEventCoordinator.class);
        var lease = mock(org.dromara.quant.reconcile.mapper.LiveUserStreamLeaseMapper.class);
        when(lease.acquireOrRenew(anyLong(), anyString(), anyInt())).thenReturn(1);
        var stream = new LivePositionStreamTriggers(credentials, hub, coordinator,
            new LivePositionFreshness(), lease);
        var account = new QuantCredential();
        account.setId(94L); account.setStatus("0"); account.setRunMode("LIVE");
        when(credentials.selectList(any())).thenReturn(List.of(account));
        when(hub.subscribe(eq(account), any())).thenThrow(new IllegalStateException("Cannot open private stream"));
        stream.supervise();
        verify(hub).unregisterLeaseOwner(eq(94L), anyString());
        verify(lease).release(eq(94L), anyString());
        verifyNoInteractions(coordinator);
    }

    @Test
    void freshLeaseEnrollmentRevokesPreviouslyVerifiedPositionSnapshot() {
        var credentials = mock(QuantCredentialMapper.class);
        var hub = mock(UserDataStreamHub.class);
        var coordinator = mock(LivePositionEventCoordinator.class);
        var lease = mock(org.dromara.quant.reconcile.mapper.LiveUserStreamLeaseMapper.class);
        var freshness = new LivePositionFreshness();
        var stream = new LivePositionStreamTriggers(credentials, hub, coordinator, freshness, lease);
        var account = new QuantCredential();
        account.setId(96L);
        account.setStatus("0");
        account.setRunMode("LIVE");
        freshness.verified(96L);
        org.junit.jupiter.api.Assertions.assertTrue(freshness.fresh(96L));
        when(credentials.selectList(any())).thenReturn(List.of(account));
        when(lease.acquireOrRenew(anyLong(), anyString(), anyInt())).thenReturn(1);
        when(hub.subscribe(eq(account), any())).thenReturn(mock(AutoCloseable.class));
        stream.supervise();
        org.junit.jupiter.api.Assertions.assertFalse(freshness.fresh(96L));
        verify(coordinator).request(96L);
        stream.close();
    }

    @Test
    void previouslyLostHubStreamIsResubscribedAfterLeaseReacquisition() throws Exception {
        var credentials = mock(QuantCredentialMapper.class);
        var hub = mock(UserDataStreamHub.class);
        var coordinator = mock(LivePositionEventCoordinator.class);
        var lease = mock(org.dromara.quant.reconcile.mapper.LiveUserStreamLeaseMapper.class);
        var stream = new LivePositionStreamTriggers(credentials, hub, coordinator,
            new LivePositionFreshness(), lease);
        var account = new QuantCredential();
        account.setId(95L);
        account.setStatus("0");
        account.setRunMode("LIVE");
        when(credentials.selectList(any())).thenReturn(List.of(account));
        when(lease.acquireOrRenew(anyLong(), anyString(), anyInt())).thenReturn(1);
        when(hub.hasLocalStream(95L)).thenReturn(false);
        var first = mock(AutoCloseable.class);
        var replacement = mock(AutoCloseable.class);
        when(hub.subscribe(eq(account), any())).thenReturn(first, replacement);
        stream.supervise();
        stream.supervise();
        verify(first).close();
        verify(hub, times(2)).subscribe(eq(account), any());
        verify(coordinator, times(2)).request(95L);
        stream.close();
        verify(replacement).close();
    }

}

