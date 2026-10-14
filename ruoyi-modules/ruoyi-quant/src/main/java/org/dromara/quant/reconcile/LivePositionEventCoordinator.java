package org.dromara.quant.reconcile;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.quant.credential.domain.QuantCredential;
import org.dromara.quant.credential.mapper.QuantCredentialMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Nonblocking callback-to-REST bridge. WebSocket callbacks merely flag an
 * account; one fixed-delay worker coalesces bursts and executes REST off the
 * stream thread. The periodic full-position heartbeat remains authoritative
 * if callbacks are lost or the pending set cannot be drained.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LivePositionEventCoordinator {
    private final QuantCredentialMapper credentials;
    private final TradingReconciliationService reconciliation;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private LivePositionFreshness freshness;
    private final Set<Long> pending = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<Long, Long> retryNotBefore = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Integer> failures = new ConcurrentHashMap<>();
    private static final long MIN_REFRESH_GAP_MILLIS = 2000L;
    private final ConcurrentHashMap<Long, Long> lastAttempt = new ConcurrentHashMap<>();

    public void request(Long credentialId) {
        if (credentialId != null) {
            // A stream event may make the previously verified snapshot stale.
            // No further LIVE opening until a full REST snapshot commits.
            if (freshness != null) {
                freshness.invalidate(credentialId);
            }
            pending.add(credentialId);
        }
    }

    @Scheduled(fixedDelay = 2000, initialDelay = 3000)
    public void drain() {
        for (Long id : Set.copyOf(pending)) {
            long now = System.currentTimeMillis();
            if (now < retryNotBefore.getOrDefault(id, 0L)
                || now - lastAttempt.getOrDefault(id, 0L) < MIN_REFRESH_GAP_MILLIS
                || !pending.remove(id)) {
                continue;
            }
            lastAttempt.put(id, now);
            try {
                QuantCredential credential = credentials.selectById(id);
                if (credential == null || !"0".equals(credential.getStatus())
                    || !"LIVE".equalsIgnoreCase(credential.getRunMode())) {
                    failures.remove(id);
                    retryNotBefore.remove(id);
                    continue;
                }
                reconciliation.refreshPositionsOnly(credential);
                failures.remove(id);
                retryNotBefore.remove(id);
            } catch (RuntimeException ex) {
                // Failed refresh never marks an account fresh. Periodic REST
                // heartbeat retries independently; allow next event to queue.
                int count = failures.merge(id, 1, (previous, ignored) -> Math.min(10, previous + 1));
                long backoffMillis = Math.min(60000L, 2000L << Math.min(count - 1, 5));
                retryNotBefore.put(id, System.currentTimeMillis() + backoffMillis);
                pending.add(id);
                log.warn("LIVE event-triggered position refresh failed credentialId={} retryInMs={}",
                    id, backoffMillis, ex);
            }
        }
    }
}
