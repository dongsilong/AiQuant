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
    private final Set<Long> pending = ConcurrentHashMap.newKeySet();

    public void request(Long credentialId) {
        if (credentialId != null) {
            pending.add(credentialId);
        }
    }

    @Scheduled(fixedDelay = 2000, initialDelay = 3000)
    public void drain() {
        for (Long id : Set.copyOf(pending)) {
            if (!pending.remove(id)) {
                continue;
            }
            try {
                QuantCredential credential = credentials.selectById(id);
                if (credential == null || !"0".equals(credential.getStatus())
                    || !"LIVE".equalsIgnoreCase(credential.getRunMode())) {
                    continue;
                }
                reconciliation.refreshPositionsOnly(credential);
            } catch (RuntimeException ex) {
                // Failed refresh never marks an account fresh. Periodic REST
                // heartbeat retries independently; allow next event to queue.
                log.warn("LIVE event-triggered position refresh failed credentialId={}", id, ex);
            }
        }
    }
}
