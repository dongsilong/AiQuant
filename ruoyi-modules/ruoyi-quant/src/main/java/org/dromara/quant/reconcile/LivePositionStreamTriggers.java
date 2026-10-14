package org.dromara.quant.reconcile;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.quant.credential.domain.QuantCredential;
import org.dromara.quant.credential.mapper.QuantCredentialMapper;
import org.dromara.quant.exchange.ExchangeGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Optional independent LIVE user stream position triggers. Activate only
 * after checking whether the existing account-stream supervisor already
 * subscribes to these credentials: duplicate listenKeys waste exchange quota.
 * REST heartbeat remains the fallback while subscription is unavailable.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "quant.position-sync", name = "event-driven", havingValue = "true")
public class LivePositionStreamTriggers {
    private final QuantCredentialMapper credentials;
    private final ExchangeGateway gateway;
    private final LivePositionEventCoordinator coordinator;
    private final LivePositionFreshness freshness;
    private final Map<Long, ExchangeGateway.UserDataStreamSession> sessions = new HashMap<>();
    private final Map<Long, java.time.Instant> renewed = new HashMap<>();

    @Scheduled(fixedDelay = 30000, initialDelay = 10000)
    public synchronized void supervise() {
        var active = credentials.selectList(new LambdaQueryWrapper<QuantCredential>()
            .eq(QuantCredential::getStatus, "0"));
        Set<Long> liveIds = new java.util.HashSet<>();
        for (QuantCredential credential : active) {
            if (!"LIVE".equalsIgnoreCase(credential.getRunMode())) {
                continue;
            }
            Long id = credential.getId();
            liveIds.add(id);
            ExchangeGateway.UserDataStreamSession session = sessions.get(id);
            if (session != null && session.isConnected()) {
                var last = renewed.get(id);
                if (last != null && java.time.Duration.between(last, java.time.Instant.now()).toMinutes() < 20) {
                    continue;
                }
                try {
                    session.keepAlive();
                    renewed.put(id, java.time.Instant.now());
                    continue;
                } catch (RuntimeException ex) {
                    log.warn("LIVE user stream listenKey renewal failed credentialId={}", id, ex);
                }
            }
            if (session != null) {
                sessions.remove(id);
                renewed.remove(id);
                try { session.close(); } catch (RuntimeException ex) {
                    log.warn("Closing disconnected LIVE user stream failed credentialId={}", id, ex);
                }
            }
            freshness.invalidate(id);
            try {
                var exchangeCredential = new ExchangeGateway.Credential(
                    credential.getApiKey(), credential.getSecretKey(),
                    credential.getSignatureType(), credential.getRunMode());
                var bound = gateway.subscribeUserData(exchangeCredential, event -> {
                    if (event != null && ("ACCOUNT_UPDATE".equals(event.eventType())
                        || "ORDER_TRADE_UPDATE".equals(event.eventType()))) {
                        // Never perform Binance REST from the WebSocket callback.
                        coordinator.request(id);
                    }
                });
                sessions.put(id, bound);
                renewed.put(id, java.time.Instant.now());
                coordinator.request(id); // refresh after reconnect / subscription
            } catch (RuntimeException ex) {
                log.warn("LIVE user stream subscribe failed credentialId={}", id, ex);
            }
        }
        for (Long id : Set.copyOf(sessions.keySet())) {
            if (!liveIds.contains(id)) {
                try { sessions.remove(id).close(); } catch (RuntimeException ex) {
                    log.warn("Closing disabled LIVE user stream failed credentialId={}", id, ex);
                }
                renewed.remove(id);
                freshness.invalidate(id);
            }
        }
    }

    @PreDestroy
    public synchronized void close() {
        for (var session : sessions.values()) {
            try { session.close(); } catch (RuntimeException ex) {
                log.warn("Closing LIVE user stream during shutdown failed", ex);
            }
        }
        sessions.clear();
        renewed.clear();
    }
}
