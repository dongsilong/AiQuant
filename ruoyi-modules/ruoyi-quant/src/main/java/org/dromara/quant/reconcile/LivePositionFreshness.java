package org.dromara.quant.reconcile;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-node gate: only a completed authenticated FULL Binance position snapshot
 * permits new LIVE risk exposure. No success is inherited across a process restart.
 * Multi-node deployments require every node to refresh at startup.
 */
@Slf4j
@Component
public class LivePositionFreshness {
    private static final Duration MAX_AGE = Duration.ofSeconds(120);
    private final ConcurrentHashMap<Long, Instant> verified = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, java.util.concurrent.atomic.AtomicLong> generations = new ConcurrentHashMap<>();
    private final java.util.Set<Long> privateStreamRequired = ConcurrentHashMap.newKeySet();
    private final java.util.Set<Long> privateStreamConnected = ConcurrentHashMap.newKeySet();

    /**
     * 返回账户当前持仓事实代次，用于拒绝过期刷新结果。
     */
    public long generation(Long credentialId) {
        return generations.computeIfAbsent(credentialId, ignored -> new java.util.concurrent.atomic.AtomicLong()).get();
    }

    /**
     * Only release LIVE entry gate if no stream event arrived during this REST snapshot.
     */
    public boolean verifiedIfUnchanged(Long credentialId, long expectedGeneration) {
        synchronized (generations.computeIfAbsent(credentialId, ignored -> new java.util.concurrent.atomic.AtomicLong())) {
            if (generation(credentialId) != expectedGeneration || !privateStreamReady(credentialId)) {
                return false;
            }
            verified.put(credentialId, Instant.now());
            return true;
        }
    }

    /**
     * 将账户纳入 LIVE 私有流连接门禁；新订阅未完成握手前不得释放开仓准入。
     */
    public void requirePrivateStream(Long credentialId, String reason) {
        if (credentialId == null) {
            return;
        }
        var counter = generations.computeIfAbsent(credentialId, ignored -> new java.util.concurrent.atomic.AtomicLong());
        synchronized (counter) {
            boolean newlyRequired = privateStreamRequired.add(credentialId);
            boolean wasConnected = privateStreamConnected.remove(credentialId);
            boolean hadVerifiedSnapshot = verified.remove(credentialId) != null;
            if (newlyRequired || wasConnected || hadVerifiedSnapshot) {
                invalidateLocked(credentialId, counter, reason);
            }
        }
    }

    /**
     * 只有真实 WebSocket CONNECTED 事件才能打开连接侧门禁；随后仍需新的完整 REST 持仓核验。
     */
    public void privateStreamConnected(Long credentialId) {
        if (credentialId == null) {
            return;
        }
        var counter = generations.computeIfAbsent(credentialId, ignored -> new java.util.concurrent.atomic.AtomicLong());
        synchronized (counter) {
            if (privateStreamRequired.add(credentialId)) {
                invalidateLocked(credentialId, counter, "PRIVATE_STREAM_REQUIRED");
            }
            privateStreamConnected.add(credentialId);
        }
    }

    /**
     * 私有流断开时同时关闭连接侧门禁并撤销当前持仓核验。
     */
    public void privateStreamDisconnected(Long credentialId, String reason) {
        if (credentialId == null) {
            return;
        }
        var counter = generations.computeIfAbsent(credentialId, ignored -> new java.util.concurrent.atomic.AtomicLong());
        synchronized (counter) {
            boolean newlyRequired = privateStreamRequired.add(credentialId);
            boolean wasConnected = privateStreamConnected.remove(credentialId);
            boolean hadVerifiedSnapshot = verified.remove(credentialId) != null;
            if (newlyRequired || wasConnected || hadVerifiedSnapshot) {
                invalidateLocked(credentialId, counter, reason);
            }
        }
    }

    /**
     * LIVE 凭证停用或切换到非 LIVE 模式后移除专属私有流门禁。
     */
    public void clearPrivateStreamRequirement(Long credentialId, String reason) {
        if (credentialId == null) {
            return;
        }
        var counter = generations.computeIfAbsent(credentialId, ignored -> new java.util.concurrent.atomic.AtomicLong());
        synchronized (counter) {
            boolean wasRequired = privateStreamRequired.remove(credentialId);
            privateStreamConnected.remove(credentialId);
            boolean hadVerifiedSnapshot = verified.remove(credentialId) != null;
            if (wasRequired || hadVerifiedSnapshot) {
                invalidateLocked(credentialId, counter, reason);
            }
        }
    }

    private boolean privateStreamReady(Long credentialId) {
        return !privateStreamRequired.contains(credentialId) || privateStreamConnected.contains(credentialId);
    }

    private void invalidateLocked(Long credentialId, java.util.concurrent.atomic.AtomicLong counter, String reason) {
        long previous = counter.getAndIncrement();
        verified.remove(credentialId);
        log.debug("LIVE position snapshot invalidated credentialId={} reason={} generation={}->{}",
            credentialId, reason == null ? "UNSPECIFIED" : reason, previous, previous + 1);
    }

    /**
     * 将快照代次检查与数据库提交串行化，避免私有流事件在检查后、事务提交前推进代次。
     * action 执行期间，新事件的 invalidate() 会等待；提交完成后事件再推进代次并触发新一轮核实。
     */
    public boolean runIfGenerationUnchanged(Long credentialId, long expectedGeneration, Runnable action) {
        if (credentialId == null || action == null) {
            throw new IllegalArgumentException("credentialId and action are required");
        }
        var counter = generations.computeIfAbsent(credentialId, ignored -> new java.util.concurrent.atomic.AtomicLong());
        synchronized (counter) {
            if (counter.get() != expectedGeneration || !privateStreamReady(credentialId)) {
                return false;
            }
            action.run();
            return true;
        }
    }

    /**
     * 标记本代持仓事实已经由交易所完整核验。
     */
    public void verified(Long credentialId) {
        if (credentialId != null) {
            var counter = generations.computeIfAbsent(credentialId, ignored -> new java.util.concurrent.atomic.AtomicLong());
            synchronized (counter) {
                if (privateStreamReady(credentialId)) {
                    verified.put(credentialId, Instant.now());
                }
            }
        }
    }

    /**
     * 使当前持仓快照失效并推进事实代次。
     */
    public void invalidate(Long credentialId) {
        invalidate(credentialId, "UNSPECIFIED");
    }

    /**
     * 使当前持仓快照失效并记录事实来源，便于定位不必要的代次推进。
     */
    public void invalidate(Long credentialId, String reason) {
        if (credentialId != null) {
            var counter = generations.computeIfAbsent(credentialId, ignored -> new java.util.concurrent.atomic.AtomicLong());
            synchronized (counter) {
                invalidateLocked(credentialId, counter, reason);
            }
        }
    }

    /**
     * 判断账户持仓事实是否仍处于已核验状态。
     */
    public boolean fresh(Long credentialId) {
        if (credentialId == null) {
            return false;
        }
        var counter = generations.computeIfAbsent(credentialId, ignored -> new java.util.concurrent.atomic.AtomicLong());
        synchronized (counter) {
            return privateStreamReady(credentialId) && isFresh(verified.get(credentialId));
        }
    }

    /**
     * 捕获当前仍新鲜的全账户持仓快照代次；代次变化代表核验后出现了新账户事实。
     */
    public long requireFreshGeneration(Long credentialId) {
        if (credentialId == null) {
            throw new IllegalStateException("缺少 LIVE 账户持仓快照身份");
        }
        var counter = generations.computeIfAbsent(credentialId, ignored -> new java.util.concurrent.atomic.AtomicLong());
        synchronized (counter) {
            if (!privateStreamReady(credentialId) || !isFresh(verified.get(credentialId))) {
                throw new IllegalStateException("LIVE 私有流未连接或账户级持仓快照缺失、过期");
            }
            return counter.get();
        }
    }

    /**
     * 发单前要求全账户持仓快照仍新鲜，且自开仓准入后没有收到使其失效的新事件。
     */
    public void requireFreshGeneration(Long credentialId, long expectedGeneration) {
        if (credentialId == null) {
            throw new IllegalStateException("缺少 LIVE 账户持仓快照身份");
        }
        var counter = generations.computeIfAbsent(credentialId, ignored -> new java.util.concurrent.atomic.AtomicLong());
        synchronized (counter) {
            if (counter.get() != expectedGeneration || !privateStreamReady(credentialId) || !isFresh(verified.get(credentialId))) {
                throw new IllegalStateException("LIVE 私有流或账户级持仓快照在开仓准备期间失效或发生变化");
            }
        }
    }

    private static boolean isFresh(Instant at) {
        Instant now = Instant.now();
        return at != null && !at.isAfter(now) && Duration.between(at, now).compareTo(MAX_AGE) <= 0;
    }
}
