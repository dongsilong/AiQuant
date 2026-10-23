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
    void startupRunnerEstablishesLiveStreamBeforeScheduledStrategyTick() throws Exception {
        var credentials = mock(QuantCredentialMapper.class);
        var hub = mock(UserDataStreamHub.class);
        var coordinator = mock(LivePositionEventCoordinator.class);
        var leaseService = mock(LiveUserStreamLeaseService.class);
        var account = new QuantCredential();
        account.setId(108L);
        account.setStatus("0");
        account.setRunMode("LIVE");
        when(credentials.selectList(any())).thenReturn(List.of(account));
        when(leaseService.acquire(eq(108L), anyInt())).thenReturn(1L);
        var reference = mock(AutoCloseable.class);
        when(hub.subscribe(eq(account), any())).thenReturn(reference);
        var stream = new LivePositionStreamTriggers(credentials, hub, coordinator, new LivePositionFreshness(), leaseService);
        try {
            stream.run(new org.springframework.boot.DefaultApplicationArguments());
            verify(hub).registerLeaseOwner(108L, 1L);
            verify(hub).subscribe(eq(account), any());
            verify(coordinator).request(108L);
        } finally {
            stream.close();
        }
    }

    @Test
    void existingHubHoldsOnlyOneAccountReferenceAcrossRepeatedSupervision() throws Exception {
        var credentials = mock(QuantCredentialMapper.class);
        var hub = mock(UserDataStreamHub.class);
        when(hub.hasLocalStream(anyLong())).thenReturn(true);
        var coordinator = mock(LivePositionEventCoordinator.class);
        var freshness = new LivePositionFreshness();
        var leaseService = mock(LiveUserStreamLeaseService.class);
        when(leaseService.acquire(anyLong(), anyInt())).thenReturn(1L);
        var stream = new LivePositionStreamTriggers(credentials, hub, coordinator, freshness, leaseService);
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
        verify(hub, times(2)).registerLeaseOwner(71L, 1L);
        stream.close();
        verify(subscription).close();
    }

    @Test
    void sameProcessLeaseEpochChangeRebuildsStreamAndInvalidatesSnapshot() throws Exception {
        var credentials = mock(QuantCredentialMapper.class);
        var hub = mock(UserDataStreamHub.class);
        when(hub.hasLocalStream(110L)).thenReturn(true);
        var coordinator = mock(LivePositionEventCoordinator.class);
        var freshness = new LivePositionFreshness();
        var leaseService = mock(LiveUserStreamLeaseService.class);
        when(leaseService.acquire(110L, 75)).thenReturn(4L, 5L);
        var stream = new LivePositionStreamTriggers(credentials, hub, coordinator, freshness, leaseService);
        var account = new QuantCredential();
        account.setId(110L);
        account.setStatus("0");
        account.setRunMode("LIVE");
        when(credentials.selectList(any())).thenReturn(List.of(account));
        var oldSubscription = mock(AutoCloseable.class);
        var newSubscription = mock(AutoCloseable.class);
        when(hub.subscribe(eq(account), any())).thenReturn(oldSubscription, newSubscription);

        stream.supervise();
        freshness.verified(110L);
        stream.supervise();

        verify(oldSubscription).close();
        verify(hub).unregisterLeaseOwner(110L, 4L);
        verify(hub).registerLeaseOwner(110L, 5L);
        verify(hub, times(2)).subscribe(eq(account), any());
        verify(coordinator, times(2)).request(110L);
        org.junit.jupiter.api.Assertions.assertFalse(freshness.fresh(110L));
        stream.close();
        verify(newSubscription).close();
    }

    @Test
    void disabledAccountReleasesHubSubscription() throws Exception {
        var credentials = mock(QuantCredentialMapper.class);
        var hub = mock(UserDataStreamHub.class);
        when(hub.hasLocalStream(anyLong())).thenReturn(true);
        var coordinator = mock(LivePositionEventCoordinator.class);
        var leaseService = mock(LiveUserStreamLeaseService.class);
        when(leaseService.acquire(anyLong(), anyInt())).thenReturn(1L);
        var stream = new LivePositionStreamTriggers(credentials, hub, coordinator, new LivePositionFreshness(), leaseService);
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
        verify(hub).unregisterLeaseOwner(72L, 1L);
    }

    @Test
    void foreignOwnerNeverCreatesSharedStreamAndRevokesPriorReference() throws Exception {
        var credentials = mock(QuantCredentialMapper.class);
        var hub = mock(UserDataStreamHub.class);
        when(hub.hasLocalStream(anyLong())).thenReturn(true);
        var coordinator = mock(LivePositionEventCoordinator.class);
        var leaseService = mock(LiveUserStreamLeaseService.class);
        var freshness = new LivePositionFreshness();
        var stream = new LivePositionStreamTriggers(credentials, hub, coordinator, freshness, leaseService);
        var account = new QuantCredential();
        account.setId(91L);
        account.setStatus("0");
        account.setRunMode("LIVE");
        when(credentials.selectList(any())).thenReturn(List.of(account));
        when(leaseService.acquire(anyLong(), anyInt())).thenReturn(1L, (Long) null);
        var reference = mock(AutoCloseable.class);
        when(hub.subscribe(eq(account), any())).thenReturn(reference);
        stream.supervise();
        freshness.verified(91L);
        stream.supervise();
        verify(hub, times(1)).subscribe(eq(account), any());
        verify(reference).close();
        verify(hub).unregisterLeaseOwner(91L, 1L);
        org.junit.jupiter.api.Assertions.assertFalse(freshness.fresh(91L));
    }

    @Test
    void failedHubSubscriptionReleasesDbLeaseImmediately() {
        var credentials = mock(QuantCredentialMapper.class);
        var hub = mock(UserDataStreamHub.class);
        when(hub.hasLocalStream(anyLong())).thenReturn(true);
        var coordinator = mock(LivePositionEventCoordinator.class);
        var leaseService = mock(LiveUserStreamLeaseService.class);
        when(leaseService.acquire(anyLong(), anyInt())).thenReturn(1L);
        var stream = new LivePositionStreamTriggers(credentials, hub, coordinator, new LivePositionFreshness(), leaseService);
        var account = new QuantCredential();
        account.setId(94L);
        account.setStatus("0");
        account.setRunMode("LIVE");
        when(credentials.selectList(any())).thenReturn(List.of(account));
        when(hub.subscribe(eq(account), any())).thenThrow(new IllegalStateException("Cannot open private stream"));
        stream.supervise();
        verify(hub).unregisterLeaseOwner(94L, 1L);
        verify(leaseService).release(94L);
        verifyNoInteractions(coordinator);
    }

    @Test
    void freshLeaseEnrollmentRevokesPreviouslyVerifiedPositionSnapshot() {
        var credentials = mock(QuantCredentialMapper.class);
        var hub = mock(UserDataStreamHub.class);
        var coordinator = mock(LivePositionEventCoordinator.class);
        var leaseService = mock(LiveUserStreamLeaseService.class);
        var freshness = new LivePositionFreshness();
        var stream = new LivePositionStreamTriggers(credentials, hub, coordinator, freshness, leaseService);
        var account = new QuantCredential();
        account.setId(96L);
        account.setStatus("0");
        account.setRunMode("LIVE");
        freshness.verified(96L);
        org.junit.jupiter.api.Assertions.assertTrue(freshness.fresh(96L));
        when(credentials.selectList(any())).thenReturn(List.of(account));
        when(leaseService.acquire(anyLong(), anyInt())).thenReturn(1L);
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
        var leaseService = mock(LiveUserStreamLeaseService.class);
        var stream = new LivePositionStreamTriggers(credentials, hub, coordinator, new LivePositionFreshness(), leaseService);
        var account = new QuantCredential();
        account.setId(95L);
        account.setStatus("0");
        account.setRunMode("LIVE");
        when(credentials.selectList(any())).thenReturn(List.of(account));
        when(leaseService.acquire(anyLong(), anyInt())).thenReturn(1L);
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

    @Test
    void failedCredentialDiscoveryRevokesStreamAndSnapshotInsteadOfRenewingStaleLease() throws Exception {
        var credentials = mock(QuantCredentialMapper.class);
        var hub = mock(UserDataStreamHub.class);
        var coordinator = mock(LivePositionEventCoordinator.class);
        var leaseService = mock(LiveUserStreamLeaseService.class);
        var freshness = new LivePositionFreshness();
        var stream = new LivePositionStreamTriggers(credentials, hub, coordinator, freshness, leaseService);
        var account = new QuantCredential();
        account.setId(97L);
        account.setStatus("0");
        account.setRunMode("LIVE");
        when(credentials.selectList(any())).thenReturn(List.of(account)).thenThrow(new IllegalStateException("database unavailable"));
        when(leaseService.acquire(anyLong(), anyInt())).thenReturn(1L);
        var reference = mock(AutoCloseable.class);
        when(hub.subscribe(eq(account), any())).thenReturn(reference);
        stream.supervise();
        freshness.verified(97L);
        stream.supervise();
        verify(reference).close();
        verify(hub).unregisterLeaseOwner(97L, 1L);
        verify(leaseService).release(97L);
        org.junit.jupiter.api.Assertions.assertFalse(freshness.fresh(97L));
        verify(leaseService, times(1)).acquire(eq(97L), anyInt());
    }

    @Test
    void failedLeaseReleaseOnOneDisabledAccountDoesNotKeepAnotherAccountOpen() throws Exception {
        var credentials = mock(QuantCredentialMapper.class);
        var hub = mock(UserDataStreamHub.class);
        when(hub.hasLocalStream(anyLong())).thenReturn(true);
        var coordinator = mock(LivePositionEventCoordinator.class);
        var leaseService = mock(LiveUserStreamLeaseService.class);
        var freshness = new LivePositionFreshness();
        var stream = new LivePositionStreamTriggers(credentials, hub, coordinator, freshness, leaseService);
        var firstAccount = new QuantCredential();
        firstAccount.setId(101L);
        firstAccount.setStatus("0");
        firstAccount.setRunMode("LIVE");
        var secondAccount = new QuantCredential();
        secondAccount.setId(102L);
        secondAccount.setStatus("0");
        secondAccount.setRunMode("LIVE");
        when(credentials.selectList(any())).thenReturn(List.of(firstAccount, secondAccount), List.of());
        when(leaseService.acquire(anyLong(), anyInt())).thenReturn(1L);
        var firstSubscription = mock(AutoCloseable.class);
        var secondSubscription = mock(AutoCloseable.class);
        when(hub.subscribe(eq(firstAccount), any())).thenReturn(firstSubscription);
        when(hub.subscribe(eq(secondAccount), any())).thenReturn(secondSubscription);
        stream.supervise();
        freshness.verified(101L);
        freshness.verified(102L);
        doThrow(new IllegalStateException("MySQL unavailable")).when(leaseService).release(anyLong());
        stream.supervise();
        verify(firstSubscription).close();
        verify(secondSubscription).close();
        org.junit.jupiter.api.Assertions.assertFalse(freshness.fresh(101L));
        org.junit.jupiter.api.Assertions.assertFalse(freshness.fresh(102L));
    }

}
