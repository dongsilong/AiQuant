package org.dromara.quant.reconcile;

import lombok.RequiredArgsConstructor;
import org.dromara.quant.reconcile.mapper.LiveUserStreamLeaseMapper;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns the process identity and the current database fencing epoch for each LIVE account.
 */
@Service
@RequiredArgsConstructor
public class LiveUserStreamLeaseService {
    private final LiveUserStreamLeaseMapper mapper;
    private final String ownerToken = UUID.randomUUID().toString();
    private final Map<Long, Long> acquiredEpochs = new ConcurrentHashMap<>();

    /** Acquire or renew the account lease, returning its fencing epoch or {@code null} on conflict. */
    public Long acquire(Long credentialId, int seconds) {
        mapper.ensure(credentialId, ownerToken, seconds);
        if (mapper.acquireOrRenew(credentialId, ownerToken, seconds) != 1) {
            acquiredEpochs.remove(credentialId);
            return null;
        }
        Long epoch = mapper.currentEpoch(credentialId, ownerToken);
        if (epoch == null) {
            acquiredEpochs.remove(credentialId);
            return null;
        }
        acquiredEpochs.put(credentialId, epoch);
        return epoch;
    }

    /** Check that this process still owns the exact epoch previously acquired. */
    public boolean owns(Long credentialId, long epoch) {
        return credentialId != null && acquiredEpochs.getOrDefault(credentialId, Long.MIN_VALUE) == epoch
            && mapper.ownsEpoch(credentialId, ownerToken, epoch) == 1;
    }

    /** Return the currently owned fencing epoch, failing closed on lease loss. */
    public long requireCurrentEpoch(Long credentialId) {
        Long epoch = credentialId == null ? null : acquiredEpochs.get(credentialId);
        if (epoch == null) {
            throw new IllegalStateException("LIVE user stream lease is not owned by this node");
        }
        requireCurrentEpoch(credentialId, epoch);
        return epoch;
    }

    /** Require exact current ownership, failing closed on a missing or stale lease. */
    public void requireCurrentEpoch(Long credentialId, long epoch) {
        if (!owns(credentialId, epoch)) {
            throw new IllegalStateException("LIVE user stream lease epoch is no longer owned by this node");
        }
    }

    /** Release this process's active epoch and its database lease. */
    public void release(Long credentialId) {
        acquiredEpochs.remove(credentialId);
        mapper.release(credentialId, ownerToken);
    }

    /** Diagnostic lease state without exposing the process ownership token. */
    public String describe(Long credentialId) {
        return mapper.describe(credentialId);
    }
}
