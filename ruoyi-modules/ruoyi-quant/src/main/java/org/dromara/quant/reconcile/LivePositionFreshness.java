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
     * 接受代次未变化的完整 REST 快照；只有私有流已连接时才记录为开仓可用快照。
     */
    public boolean verifiedIfUnchanged(Long credentialId, long expectedGeneration) {
        synchronized (generations.computeIfAbsent(credentialId, ignored -> new java.util.concurrent.atomic.AtomicLong())) {
            if (generation(credentialId) != expectedGeneration) {
                return false;
            }
            if (privateStreamReady(credentialId)) {
                verified.put(credentialId, Instant.now());
            } else {
                // REST 事实仍可用于更新本地持仓，但不能在私有流未连接时释放 LIVE 开仓门禁。
                verified.remove(credentialId);
            }
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
     * 真实 WebSocket 连接状态发生变化时推进代次，拒绝连接建立前启动的 REST 快照。
     */
    public void privateStreamConnected(Long credentialId) {
        if (credentialId == null) {
            return;
        }
        var counter = generations.computeIfAbsent(credentialId, ignored -> new java.util.concurrent.atomic.AtomicLong());
        synchronized (counter) {
            boolean newlyRequired = privateStreamRequired.add(credentialId);
            boolean newlyConnected = privateStreamConnected.add(credentialId);
            if (newlyRequired || newlyConnected) {
                invalidateLocked(credentialId, counter, newlyRequired ? "PRIVATE_STREAM_REQUIRED" : "PRIVATE_STREAM_CONNECTED");
            }
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
        log.debug("实时仓位快照已失效 credentialId={} reason={} generation={}->{}",
            credentialId, reason == null ? "UNSPECIFIED" : reason, previous, previous + 1);
    }

    /**
     * 将快照代次检查与数据库提交串行化。私有流连接状态单独控制开仓门禁，
     * 不应阻止无竞争的 REST 事实落库；连接变化会推进代次并使旧快照失效。
     */
    public boolean runIfGenerationUnchanged(Long credentialId, long expectedGeneration, Runnable action) {
        if (credentialId == null || action == null) {
            throw new IllegalArgumentException("credentialId and action are required");
        }
        var counter = generations.computeIfAbsent(credentialId, ignored -> new java.util.concurrent.atomic.AtomicLong());
        synchronized (counter) {
            if (counter.get() != expectedGeneration) {
                return false;
            }
            action.run();
            return true;
        }
    }

    /**
     * 标记本代持仓事实已经由交易所完整核验；私有流未连接时不开放开仓准入。
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
