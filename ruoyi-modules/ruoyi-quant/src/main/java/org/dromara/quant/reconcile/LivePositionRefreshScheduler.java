package org.dromara.quant.reconcile;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.quant.credential.domain.QuantCredential;
import org.dromara.quant.credential.mapper.QuantCredentialMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;

import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Periodic FULL position truth heartbeat, independent of order/trade-history
 * reconciliation. Never changes persisted quantities on failed REST calls.
 * A node's LIVE risk gate expires after 120s without a successful snapshot.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "quant.live", name = "enabled", havingValue = "true")
public class LivePositionRefreshScheduler {
    private final QuantCredentialMapper credentials;
    private final TradingReconciliationService reconciliation;
    /** 同一凭证同一时刻只刷新一次，避免慢请求导致重复堆积。 */
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
    /** 一个账户刷新变慢时，不阻塞其他账户的持仓快照心跳。 */
    private final ThreadPoolExecutor refreshExecutor = new ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(256), task -> {
            Thread thread = new Thread(task, "quant-live-position-refresh");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());

    /**
     * 定期触发活动 LIVE 账户的持仓事实刷新。调度线程只派发任务，不等待 Binance REST。
     */
    @Scheduled(fixedDelay = 30000, initialDelay = 30000)
    public void refresh() {
        final java.util.List<QuantCredential> enabled;
        try {
            enabled = credentials.selectList(new LambdaQueryWrapper<QuantCredential>().eq(QuantCredential::getStatus, "0"));
        } catch (RuntimeException ex) {
            log.error("LIVE 持仓心跳无法读取启用凭证列表；本轮刷新跳过", ex);
            return;
        }
        for (QuantCredential credential : enabled) {
            if (!"LIVE".equalsIgnoreCase(credential.getRunMode()) || credential.getId() == null || !inFlight.add(credential.getId())) {
                continue;
            }
            try {
                refreshExecutor.execute(() -> refreshCredential(credential));
            } catch (RejectedExecutionException rejected) {
                inFlight.remove(credential.getId());
                log.error("LIVE 持仓刷新队列已满，账户快照可能过期 credentialId={}", credential.getId(), rejected);
            }
        }
    }

    private void refreshCredential(QuantCredential credential) {
        long startedAt = System.nanoTime();
        try {
            reconciliation.refreshPositionsOnly(credential);
        } catch (TradingReconciliationService.SnapshotSupersededException ex) {
            // 新事件已使旧 REST 结果失效；事件刷新器和下一轮心跳会重新取证，不放宽新鲜度门禁。
            log.debug("LIVE 持仓心跳快照已被更新事实取代，等待重新核实 credentialId={}", credential.getId());
        } catch (RuntimeException ex) {
            log.warn("LIVE position heartbeat failed credentialId={}, new entries remain subject to snapshot TTL", credential.getId(), ex);
        } finally {
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            if (elapsedMs >= 5_000L) {
                log.warn("LIVE 持仓快照刷新耗时较长 credentialId={} elapsedMs={}", credential.getId(), elapsedMs);
            } else {
                log.debug("LIVE 持仓快照刷新任务完成 credentialId={} elapsedMs={}", credential.getId(), elapsedMs);
            }
            inFlight.remove(credential.getId());
        }
    }

    @PreDestroy
    public void destroy() {
        refreshExecutor.shutdownNow();
    }
}
