package org.dromara.quant.reconcile;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.quant.credential.domain.QuantCredential;
import org.dromara.quant.credential.mapper.QuantCredentialMapper;
import org.dromara.quant.market.UserDataStreamHub;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Keep one LIVE subscription reference per enabled credential, via the existing
 * UserDataStreamHub. The hub owns all Binance connections, listenKey renewal,
 * reconnect and fan-out; this component MUST NEVER subscribe to Binance itself.
 * Other consumers (including terminal SSE) share the same in-JVM connection.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "quant.position-sync", name = "event-driven", havingValue = "true")
public class LivePositionStreamTriggers {
    private final QuantCredentialMapper credentials;
    private final UserDataStreamHub hub;
    private final LivePositionEventCoordinator coordinator;
    private final LivePositionFreshness freshness;
    private final org.dromara.quant.reconcile.mapper.LiveUserStreamLeaseMapper lease;
    private final String ownerToken = java.util.UUID.randomUUID().toString();
    private static final int LEASE_SECONDS = 75;
    private final Map<Long, AutoCloseable> subscriptions = new HashMap<>();

    @Scheduled(fixedDelay = 30000, initialDelay = 10000)
    public synchronized void supervise() {
        Set<Long> liveIds = new HashSet<>();
        for (QuantCredential credential : credentials.selectList(
            new LambdaQueryWrapper<QuantCredential>().eq(QuantCredential::getStatus, "0"))) {
            if (!"LIVE".equalsIgnoreCase(credential.getRunMode())) {
                continue;
            }
            Long id = credential.getId();
            liveIds.add(id);
            try {
                lease.ensure(id, ownerToken, LEASE_SECONDS);
                if (lease.acquireOrRenew(id, ownerToken, LEASE_SECONDS) != 1) {
                    closeSubscription(id);
                    freshness.invalidate(id);
                    continue;
                }
                // Hub may have forcibly removed the old WebSocket after a
                // lease loss while this supervisor still holds its old handle.
                // Discard that handle and re-enroll instead of silently
                // treating the lost stream as active after lease reacquisition.
                if (subscriptions.containsKey(id) && !hub.hasLocalStream(id)) {
                    closeSubscription(id);
                }
                hub.registerLeaseOwner(id, ownerToken);
                if (subscriptions.containsKey(id)) {
                    continue;
                }
                // A new stream ownership generation cannot inherit a position
                // snapshot verified under the previous WebSocket/lease. Block
                // LIVE entries until a fresh authenticated REST snapshot wins.
                freshness.invalidate(id);
                AutoCloseable reference = hub.subscribe(credential, event -> {
                    // Hub centrally forwards events into the coordinator; this
                    // listener exists only to hold an active subscription.
                });
                subscriptions.put(id, reference);
                coordinator.request(id);
            } catch (RuntimeException ex) {
                closeSubscription(id);
                freshness.invalidate(id);
                // A failed Hub enrollment must not strand a DB lease for 75 seconds.
                // A conditional release cannot affect another node's ownership.
                try {
                    lease.release(id, ownerToken);
                } catch (RuntimeException releaseError) {
                    log.warn("LIVE shared stream lease cleanup failed credentialId={}", id, releaseError);
                }
                log.warn("LIVE shared user stream enrollment failed credentialId={}", id, ex);
            }
        }
        for (Long id : Set.copyOf(subscriptions.keySet())) {
            if (!liveIds.contains(id)) {
                closeSubscription(id);
                lease.release(id, ownerToken);
                freshness.invalidate(id);
            }
        }
    }

    private void closeSubscription(Long id) {
        hub.unregisterLeaseOwner(id, ownerToken);
        AutoCloseable subscription = subscriptions.remove(id);
        if (subscription != null) {
            try {
                subscription.close();
            } catch (Exception ex) {
                log.warn("LIVE shared stream unsubscribe failed credentialId={}", id, ex);
            }
        }
    }

    @PreDestroy
    public synchronized void close() {
        for (Long id : Set.copyOf(subscriptions.keySet())) {
            closeSubscription(id);
            lease.release(id, ownerToken);
        }
    }
}
