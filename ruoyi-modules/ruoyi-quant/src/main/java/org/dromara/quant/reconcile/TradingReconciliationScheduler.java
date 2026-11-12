package org.dromara.quant.reconcile;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.dromara.quant.config.QuantProperties;
import org.dromara.quant.credential.domain.QuantCredential;
import org.dromara.quant.credential.mapper.QuantCredentialMapper;
import org.dromara.quant.exchange.ExchangeMarketPort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 周期性交易所对账。
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class TradingReconciliationScheduler {
    private static final long MAX_RETRY_DELAY_MILLIS = 30_000L;
    private final QuantCredentialMapper credentialMapper;
    private final TradingReconciliationService reconciliationService;
    private final QuantProperties properties;
    private final ScheduledExecutorService retryExecutor = Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().daemon().name("quant-reconciliation-retry").factory());
    private final Map<Long, ScheduledFuture<?>> retryTasks = new ConcurrentHashMap<>();
    private final Map<Long, Integer> retryAttempts = new ConcurrentHashMap<>();
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    /**
     * 执行一轮账户级交易事实对账，补齐本地成交账本与执行记录。
     */
    @Scheduled(
        fixedDelayString = "#{@quantProperties.reconcile.intervalSeconds.toString()}",
        initialDelayString = "15",
        timeUnit = TimeUnit.SECONDS
    )
    public void reconcile() {
        if (!properties.getReconcile().isEnabled()) {
            return;
        }
        for (QuantCredential credential : credentialMapper.selectList(new LambdaQueryWrapper<QuantCredential>().eq(QuantCredential::getStatus, "0"))) {
            if ("PAPER".equalsIgnoreCase(credential.getRunMode())) {
                continue;
            }
            runReconciliation(credential, properties.getReconcile().getHistoryHours());
        }
    }

    private void runReconciliation(QuantCredential credential, int historyHours) {
        Long credentialId = credential.getId();
        if (credentialId == null || !properties.getReconcile().isEnabled()) {
            return;
        }
        ScheduledFuture<?> pending = retryTasks.get(credentialId);
        if (pending != null && !pending.isDone()) {
            return;
        }
        if (!inFlight.add(credentialId)) {
            return;
        }
        try {
            reconciliationService.reconcileCredential(credential, historyHours);
            clearRetry(credentialId);
        } catch (TradingReconciliationService.SnapshotSupersededException ex) {
            log.debug("账户持仓快照被更新事实取代，等待只读重试 credentialId={}", credentialId);
        } catch (ExchangeMarketPort.RequestDeferredException ex) {
            if ("MANUAL_BLOCK".equals(ex.reason()) || "INTERRUPTED".equals(ex.reason())
                || "POSITION_VERIFY".equals(ex.kind())) {
                log.warn("量化账户事实对账未执行完整；请求延期由持仓快照重试或运维处理 credentialId={} kind={} reason={} queueSize={} queueSizes={} retryAfterMs={}",
                    credentialId, ex.kind(), ex.reason(), ex.queueSize(), java.util.Arrays.toString(ex.queueSizes()), ex.retryAfterMillis());
                return;
            }
            long delay = scheduleRetry(credential, historyHours, ex.retryAfterMillis());
            log.warn("量化账户事实对账因 REST 调度延期，已安排合并重试 credentialId={} kind={} reason={} queueSize={} queueSizes={} waitedMs={} retryAfterMs={} retryDelayMs={}",
                credentialId, ex.kind(), ex.reason(), ex.queueSize(), java.util.Arrays.toString(ex.queueSizes()),
                ex.waitedMillis(), ex.retryAfterMillis(), delay);
        } catch (ExchangeMarketPort.RateLimitException ex) {
            long delay = scheduleRetry(credential, historyHours, ex.retryAfterMillis());
            log.warn("量化账户事实对账遇到 Binance 限流，已安排合并重试 credentialId={} retryAfterMs={} retryDelayMs={}",
                credentialId, ex.retryAfterMillis(), delay);
        } catch (RuntimeException ex) {
            log.error("量化账户事实对账失败 credentialId={}", credentialId, ex);
        } finally {
            inFlight.remove(credentialId);
        }
    }

    private long scheduleRetry(QuantCredential credential, int historyHours, long retryAfterMillis) {
        Long credentialId = credential.getId();
        int attempt = retryAttempts.merge(credentialId, 1, Integer::sum);
        long delay = retryDelayMillis(retryAfterMillis, attempt);
        retryTasks.compute(credentialId, (id, current) -> {
            if (current != null && !current.isDone()) {
                return current;
            }
            return retryExecutor.schedule(() -> {
                retryTasks.remove(id);
                runReconciliation(credential, historyHours);
            }, delay, TimeUnit.MILLISECONDS);
        });
        return delay;
    }

    static long retryDelayMillis(long retryAfterMillis, int attempt) {
        int exponent = Math.max(0, Math.min(attempt - 1, 5));
        long backoff = Math.min(MAX_RETRY_DELAY_MILLIS, 1_000L << exponent);
        return Math.max(Math.max(1_000L, retryAfterMillis), backoff);
    }

    private void clearRetry(Long credentialId) {
        ScheduledFuture<?> pending = retryTasks.remove(credentialId);
        if (pending != null) {
            pending.cancel(false);
        }
        retryAttempts.remove(credentialId);
    }

    @PreDestroy
    public void shutdownRetries() {
        retryExecutor.shutdownNow();
        retryTasks.values().forEach(task -> task.cancel(false));
        retryTasks.clear();
        retryAttempts.clear();
        inFlight.clear();
    }
}
