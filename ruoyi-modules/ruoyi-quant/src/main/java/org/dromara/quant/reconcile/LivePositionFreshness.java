package org.dromara.quant.reconcile;

import org.springframework.stereotype.Component;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-node gate: only a completed authenticated FULL Binance position snapshot
 * permits new LIVE risk exposure. No success is inherited across a process restart.
 * Multi-node deployments require every node to refresh at startup.
 */
@Component
public class LivePositionFreshness {
    private final ConcurrentHashMap<Long, Instant> verified = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, java.util.concurrent.atomic.AtomicLong> generations = new ConcurrentHashMap<>();

    public long generation(Long credentialId) {
        return generations.computeIfAbsent(credentialId, ignored -> new java.util.concurrent.atomic.AtomicLong()).get();
    }

    /** Only release LIVE entry gate if no stream event arrived during this REST snapshot. */
    public boolean verifiedIfUnchanged(Long credentialId, long expectedGeneration) {
        synchronized (generations.computeIfAbsent(credentialId, ignored -> new java.util.concurrent.atomic.AtomicLong())) {
            if (generation(credentialId) != expectedGeneration) return false;
            verified.put(credentialId, Instant.now());
            return true;
        }
    }
    private static final Duration MAX_AGE = Duration.ofSeconds(120);

    public void verified(Long credentialId) {
        if (credentialId != null) {
            verified.put(credentialId, Instant.now());
        }
    }

    public void invalidate(Long credentialId) {
        if (credentialId != null) {
            var counter = generations.computeIfAbsent(credentialId, ignored -> new java.util.concurrent.atomic.AtomicLong());
            synchronized (counter) {
                counter.incrementAndGet();
                verified.remove(credentialId);
            }
        }
    }

    public boolean fresh(Long credentialId) {
        Instant at = verified.get(credentialId);
        return at != null && !at.isAfter(Instant.now())
            && Duration.between(at, Instant.now()).compareTo(MAX_AGE) <= 0;
    }
}
