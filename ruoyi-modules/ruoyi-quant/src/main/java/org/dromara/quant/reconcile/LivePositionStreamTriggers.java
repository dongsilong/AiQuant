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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Keep one LIVE subscription reference per enabled credential, via the existing
 * UserDataStreamHub. The hub owns all Binance connections, listenKey renewal,
 * reconnect and fan-out; this component MUST NEVER subscribe to Binance itself.
 * Other consumers (including terminal SSE) share the same in-JVM connection.
 * LIVE lease supervision is mandatory even if event-driven position refresh is disabled.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "quant.live", name = "enabled", havingValue = "true", matchIfMissing = true)
public class LivePositionStreamTriggers implements org.springframework.boot.ApplicationRunner {
    private static final int LEASE_SECONDS = 75;
    private final QuantCredentialMapper credentials;
    private final UserDataStreamHub hub;
    private final LivePositionEventCoordinator coordinator;
    private final LivePositionFreshness freshness;
    private final LiveUserStreamLeaseService lease;
    private final Map<Long, AutoCloseable> subscriptions = new HashMap<>();
    private final Map<Long, Long> subscriptionEpochs = new HashMap<>();
    /**
     * Avoid flooding WARN when an account is leased to a different LIVE node.
     */
    private final Map<Long, Long> nextLeaseConflictLogAt = new HashMap<>();
    private final ScheduledExecutorService leaseRetryScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "quant-live-lease-retry");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<Long, ScheduledFuture<?>> leaseRetryTasks = new HashMap<>();

    /**
     * Establish the private-stream ownership before the application reports startup
     * complete and before the strategy runtime supervisor's initial tick.
     * The scheduled supervisor remains responsible for continuous renewal.
     */
    @Override
    public void run(org.springframework.boot.ApplicationArguments args) {
        supervise();
    }

    /**
     * 检查活动账户的私有流状态并按需重建订阅。
     */
    @Scheduled(fixedDelay = 30000, initialDelay = 30000)
    public synchronized void supervise() {
        Set<Long> liveIds = new HashSet<>();
        final java.util.List<QuantCredential> enabledCredentials;
        try {
            enabledCredentials = credentials.selectList(new LambdaQueryWrapper<QuantCredential>().eq(QuantCredential::getStatus, "0"));
            if (enabledCredentials == null) {
                throw new IllegalStateException("LIVE 凭证查询结果为空");
            }
        } catch (RuntimeException ex) {
            // On discovery failure we cannot establish which accounts are still
            // enabled. Stop owned LIVE streams rather than continuing with a
            // previously authorized account and a renewable stale lease.
            for (ScheduledFuture<?> task : leaseRetryTasks.values()) {
                task.cancel(false);
            }
            leaseRetryTasks.clear();
            for (Long id : Set.copyOf(subscriptions.keySet())) {
                closeSubscription(id);
                freshness.privateStreamDisconnected(id, "CREDENTIAL_DISCOVERY_FAILED");
                try {
                    lease.release(id);
                } catch (RuntimeException releaseError) {
                    log.warn("LIVE 凭证查询失败后的数据流租约清理失败 credentialId={}", id, releaseError);
                }
            }
            log.error("LIVE 用户数据流凭证查询失败，已撤销全部订阅", ex);
            return;
        }
        for (QuantCredential credential : enabledCredentials) {
            Long id = credential.getId();
            if (!"LIVE".equalsIgnoreCase(credential.getRunMode())) {
                cancelLeaseRetry(id);
                if (!subscriptions.containsKey(id)) {
                    freshness.clearPrivateStreamRequirement(id, "RUN_MODE_NOT_LIVE");
                }
                continue;
            }
            liveIds.add(id);
            try {
                Long leaseEpoch = lease.acquire(id, LEASE_SECONDS);
                if (leaseEpoch == null) {
                    long now = System.currentTimeMillis();
                    if (now >= nextLeaseConflictLogAt.getOrDefault(id, 0L)) {
                        nextLeaseConflictLogAt.put(id, now + 60_000L);
                        String leaseState;
                        try {
                            leaseState = lease.describe(id);
                        } catch (RuntimeException diagnosticError) {
                            leaseState = "租约诊断信息不可用：" + diagnosticError.getClass().getSimpleName();
                        }
                        log.warn("LIVE私有流租约竞争 credentialId={} dbLease=[{}]；本节点禁止创建交易策略运行时", id, leaseState);
                    }
                    scheduleLeaseRetry(id);
                    closeSubscription(id);
                    freshness.privateStreamDisconnected(id, "LEASE_NOT_OWNED");
                    continue;
                }
                cancelLeaseRetry(id);
                Long previousEpoch = subscriptionEpochs.get(id);
                if (previousEpoch != null && !previousEpoch.equals(leaseEpoch)) {
                    // A process can reacquire an expired lease with the same token.
                    // Epoch changes still fence the old socket and its account facts.
                    closeSubscription(id);
                    freshness.privateStreamDisconnected(id, "LEASE_EPOCH_CHANGED");
                }
                // Hub may have forcibly removed the old WebSocket after a
                // lease loss while this supervisor still holds its old handle.
                // Discard that handle and re-enroll instead of silently
                // treating the lost stream as active after lease reacquisition.
                if (subscriptions.containsKey(id) && !hub.hasLocalStream(id)) {
                    closeSubscription(id);
                }
                hub.registerLeaseOwner(id, leaseEpoch);
                subscriptionEpochs.put(id, leaseEpoch);
                if (subscriptions.containsKey(id)) {
                    continue;
                }
                // A new stream ownership generation cannot inherit a position
                // snapshot verified under the previous WebSocket/lease. Block
                // LIVE entries until a fresh authenticated REST snapshot wins.
                freshness.requirePrivateStream(id, "STREAM_SUBSCRIPTION_CREATED");
                AutoCloseable reference = hub.subscribe(credential, event -> {
                    // Hub centrally forwards events into the coordinator; this
                    // listener exists only to hold an active subscription.
                });
                subscriptions.put(id, reference);
                nextLeaseConflictLogAt.remove(id);
                log.info("LIVE私有流租约已获取并注册共享订阅 credentialId={}，等待WebSocket连接及完整持仓核验后开放开仓准入", id);
            } catch (RuntimeException ex) {
                cancelLeaseRetry(id);
                closeSubscription(id);
                freshness.privateStreamDisconnected(id, "STREAM_ENROLLMENT_FAILED");
                // A failed Hub enrollment must not strand a DB lease for 75 seconds.
                // A conditional release cannot affect another node's ownership.
                try {
                    lease.release(id);
                } catch (RuntimeException releaseError) {
                    log.warn("LIVE 共享数据流租约清理失败 credentialId={}", id, releaseError);
                }
                log.warn("LIVE 共享用户数据流订阅注册失败 credentialId={}", id, ex);
            }
        }
        for (Long id : Set.copyOf(leaseRetryTasks.keySet())) {
            if (!liveIds.contains(id)) {
                cancelLeaseRetry(id);
            }
        }
        for (Long id : Set.copyOf(subscriptions.keySet())) {
            if (!liveIds.contains(id)) {
                closeSubscription(id);
                freshness.clearPrivateStreamRequirement(id, "CREDENTIAL_DISABLED");
                try {
                    lease.release(id);
                } catch (RuntimeException ex) {
                    // Account removal must never abort cleanup of other accounts.
                    // The DB lease expires without renewal; no stale local stream remains.
                    log.warn("LIVE 账户移除后的数据流租约清理失败 credentialId={}", id, ex);
                }
            }
        }
    }

    private void scheduleLeaseRetry(Long credentialId) {
        final Long remainingMillis;
        try {
            remainingMillis = lease.remainingMillis(credentialId);
        } catch (RuntimeException ex) {
            // The regular 30-second supervisor remains the fallback if the DB diagnostic is unavailable.
            log.debug("LIVE租约剩余时间查询失败，保留常规监督重试 credentialId={}", credentialId, ex);
            return;
        }
        if (remainingMillis == null) {
            return;
        }
        cancelLeaseRetry(credentialId);
        long delayMillis = Math.max(250L, remainingMillis + 250L);
        ScheduledFuture<?>[] taskRef = new ScheduledFuture<?>[1];
        taskRef[0] = leaseRetryScheduler.schedule(() -> {
            synchronized (LivePositionStreamTriggers.this) {
                if (leaseRetryTasks.get(credentialId) != taskRef[0]) {
                    return;
                }
                leaseRetryTasks.remove(credentialId);
                log.debug("LIVE租约预计到期，提前触发账户租约竞争 credentialId={}", credentialId);
                supervise();
            }
        }, delayMillis, TimeUnit.MILLISECONDS);
        leaseRetryTasks.put(credentialId, taskRef[0]);
        log.debug("LIVE租约冲突已安排到期重试 credentialId={} remainingMs={} retryInMs={}", credentialId, remainingMillis, delayMillis);
    }

    private void cancelLeaseRetry(Long credentialId) {
        ScheduledFuture<?> task = leaseRetryTasks.remove(credentialId);
        if (task != null) {
            task.cancel(false);
        }
    }

    private void closeSubscription(Long id) {
        freshness.privateStreamDisconnected(id, "STREAM_SUBSCRIPTION_CLOSED");
        Long epoch = subscriptionEpochs.remove(id);
        if (epoch != null) {
            hub.unregisterLeaseOwner(id, epoch);
        }
        AutoCloseable subscription = subscriptions.remove(id);
        if (subscription != null) {
            try {
                subscription.close();
            } catch (Exception ex) {
                log.warn("LIVE 共享数据流取消订阅失败 credentialId={}", id, ex);
            }
        }
    }

    /**
     * 停止监督任务并释放私有流订阅。
     */
    @PreDestroy
    public synchronized void close() {
        for (ScheduledFuture<?> task : leaseRetryTasks.values()) {
            task.cancel(false);
        }
        leaseRetryTasks.clear();
        leaseRetryScheduler.shutdownNow();
        for (Long id : Set.copyOf(subscriptions.keySet())) {
            closeSubscription(id);
            freshness.privateStreamDisconnected(id, "APPLICATION_SHUTDOWN");
            try {
                lease.release(id);
            } catch (RuntimeException ex) {
                log.warn("LIVE 数据流关闭时租约清理失败 credentialId={}", id, ex);
            }
        }
    }
}
