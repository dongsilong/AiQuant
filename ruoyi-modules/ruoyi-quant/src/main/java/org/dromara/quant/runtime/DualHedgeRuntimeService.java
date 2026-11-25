package org.dromara.quant.runtime;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.quant.config.QuantProperties;
import org.dromara.quant.credential.domain.QuantCredential;
import org.dromara.quant.credential.mapper.QuantCredentialMapper;
import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.market.AccountBalanceCache;
import org.dromara.quant.market.AccountBalanceSnapshot;
import org.dromara.quant.market.MarketEventFreshness;
import org.dromara.quant.market.MarketStreamHub;
import org.dromara.quant.market.UserDataStreamHub;
import org.dromara.quant.market.domain.QuantSymbol;
import org.dromara.quant.market.mapper.QuantSymbolMapper;
import org.dromara.quant.runtime.coordination.StrategyRuntimeLeaseService;
import org.dromara.quant.runtime.domain.QuantStrategyHedgeRound;
import org.dromara.quant.runtime.service.StrategyHedgeRoundService;
import org.dromara.quant.strategy.DualHedgeSlopeSignalEvaluator;
import org.dromara.quant.strategy.domain.QuantStrategy;
import org.dromara.quant.strategy.domain.QuantStrategyInstance;
import org.dromara.quant.strategy.domain.QuantStrategySymbol;
import org.dromara.quant.strategy.mapper.QuantStrategySymbolMapper;
import org.dromara.quant.util.QuantJsonMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 双开对敲策略独立的行情订阅与运行租约生命周期。
 *
 * <p>本服务计算并持久化双开入场信号，但不执行下单、持仓管理或保护单操作。
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class DualHedgeRuntimeService {
    private final QuantStrategySymbolMapper strategySymbolMapper;
    private final QuantCredentialMapper credentialMapper;
    private final QuantSymbolMapper symbolMapper;
    private final StrategyRuntimeLeaseService leaseService;
    private final MarketStreamHub marketStreamHub;
    private final UserDataStreamHub userDataStreamHub;
    private final ExchangeGateway gateway;
    private final QuantProperties properties;
    private final AccountBalanceCache balanceCache;
    private final StrategyHedgeRoundService hedgeRoundService;

    private final Map<Long, DualHedgeRuntime> runtimes = new ConcurrentHashMap<>();
    private final ExecutorService leaseRenewalExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 返回 null 表示该实例不是双开策略；true/false 表示双开 Runtime 是否就绪。
     */
    public synchronized Boolean ensureRuntimeIfApplicable(QuantStrategyInstance instance, QuantStrategy strategy) {
        if (strategy == null || !DualHedgeSlopeSignalEvaluator.CODE.equals(strategy.getPluginCode())) {
            return null;
        }
        return ensureRuntime(instance, strategy);
    }

    private boolean ensureRuntime(QuantStrategyInstance instance, QuantStrategy strategy) {
        if (instance == null || instance.getId() == null || !"RUNNING".equals(instance.getStatus())) {
            if (instance != null) {
                stopInstance(instance.getId());
            }
            return false;
        }
        QuantStrategySymbol strategySymbol = strategySymbolMapper.selectById(instance.getStrategySymbolId());
        QuantCredential credential = credentialMapper.selectById(instance.getCredentialId());
        if (strategySymbol == null || credential == null || strategySymbol.getSymbolId() == null
            || !"1".equals(strategy.getStatus()) || !"0".equals(strategySymbol.getStatus())
            || Boolean.FALSE.equals(strategySymbol.getRuntimeEnabled()) || !"0".equals(credential.getStatus())
            || !"15m".equalsIgnoreCase(strategySymbol.getIntervalCode())
            || ("PAPER".equalsIgnoreCase(credential.getRunMode()) && !properties.getPaper().isEnabled())) {
            stopInstance(instance.getId());
            return false;
        }
        QuantSymbol symbol = symbolMapper.selectById(strategySymbol.getSymbolId());
        Map<String, Object> params;
        try {
            params = new HashMap<>(QuantJsonMapper.readMap(strategy.getParamsJson()));
            params.putAll(QuantJsonMapper.readMap(strategySymbol.getParamsJson()));
        } catch (Exception ex) {
            log.error("双开策略参数解析失败，拒绝启动 Runtime instanceId={}", instance.getId(), ex);
            stopInstance(instance.getId());
            return false;
        }
        try {
            DualHedgeSlopeSignalEvaluator.entryAngleDegrees(params);
            DualHedgeSlopeSignalEvaluator.marginRatio(params);
            Integer leverage = strategySymbol.getLeverage() == null
                ? strategy.getDefaultLeverage() : strategySymbol.getLeverage();
            if (leverage == null || leverage <= 0) {
                throw new IllegalArgumentException("双开策略杠杆必须大于0");
            }
        } catch (RuntimeException ex) {
            log.error("双开策略参数校验失败，拒绝启动 Runtime instanceId={}", instance.getId(), ex);
            stopInstance(instance.getId());
            return false;
        }
        if (symbol == null || symbol.getSymbol() == null || symbol.getSymbol().isBlank() || !"0".equals(symbol.getStatus())) {
            stopInstance(instance.getId());
            return false;
        }
        if ("LIVE".equalsIgnoreCase(credential.getRunMode())
            && !userDataStreamHub.hasLiveStreamOwnership(credential.getId())) {
            return false;
        }

        DualHedgeRuntime current = runtimes.get(instance.getId());
        if (current != null && current.matches(credential, symbol.getSymbol(), strategySymbol.getIntervalCode())) {
            StrategyRuntimeLeaseService.LeaseSnapshot snapshot = null;
            current.leaseOperationLock.lock();
            try {
                if (!current.closed) {
                    snapshot = leaseService.acquireOrRenewSnapshot(instance.getId(), credential.getId(),
                        symbol.getSymbol(), credential.getCredentialRuntimeVersion());
                }
                if (snapshot != null && !current.closed) {
                    current.leaseSnapshot = snapshot;
                    current.credential = credential;
                    current.strategy = strategy;
                    current.strategySymbol = strategySymbol;
                    current.params = params;
                }
            } finally {
                current.leaseOperationLock.unlock();
            }
            return snapshot != null || isLeaseSnapshotValid(current.leaseSnapshot);
        }

        if (current != null) {
            closeRuntime(current);
            runtimes.remove(instance.getId(), current);
        }
        StrategyRuntimeLeaseService.LeaseSnapshot snapshot = leaseService.acquireOrRenewSnapshot(
            instance.getId(), credential.getId(), symbol.getSymbol(), credential.getCredentialRuntimeVersion());
        if (snapshot == null) {
            log.warn("双开策略运行租约获取失败 instanceId={} credentialId={} symbol={}",
                instance.getId(), credential.getId(), symbol.getSymbol());
            return false;
        }

        DualHedgeRuntime runtime = new DualHedgeRuntime(instance, strategy, strategySymbol, credential, symbol, snapshot, params);
        try {
            if (!"PAPER".equalsIgnoreCase(credential.getRunMode())) {
                runtime.userSubscription = userDataStreamHub.subscribe(credential, event -> {
                    // UserDataStreamHub centrally updates AccountBalanceCache before notifying subscribers.
                });
            }
            runtime.subscription = marketStreamHub.subscribe(credential.getRunMode(), symbol.getSymbol(),
                strategySymbol.getIntervalCode(), event -> onMarketEvent(runtime, event),
                ready -> runtime.marketStreamReady = ready);
            runtimes.put(instance.getId(), runtime);
            log.info("双开策略独立 Runtime 已启动 instanceId={} credentialId={} symbol={} interval={} runMode={}",
                instance.getId(), credential.getId(), symbol.getSymbol(), strategySymbol.getIntervalCode(), credential.getRunMode());
            return true;
        } catch (RuntimeException ex) {
            closeRuntime(runtime);
            throw ex;
        }
    }

    private void onMarketEvent(DualHedgeRuntime runtime, ExchangeGateway.MarketEvent event) {
        if (runtime.closed || event == null) {
            return;
        }
        if ("CONNECTING".equals(event.type()) || "DISCONNECTED".equals(event.type()) || "ERROR".equals(event.type())) {
            runtime.marketStreamStatus = event.type();
            return;
        }
        if ("CONNECTED".equals(event.type())) {
            runtime.marketStreamStatus = event.type();
            return;
        }

        Instant localNow = Instant.now();
        Instant exchangeNow = localNow.plusMillis(gateway.exchangeTimeOffsetMillis(runtime.credential.getRunMode()));
        boolean fresh = event.eventTime() != null && event.receivedAt() != null
            && runtime.symbol.getSymbol().equalsIgnoreCase(event.symbol())
            && "15m".equalsIgnoreCase(event.interval())
            && MarketEventFreshness.isFresh(event.eventTime(), event.receivedAt(), localNow, exchangeNow,
                properties.getMarket().getMaxEventAgeMillis());
        if (event.kline() != null && (event.replay() || fresh)) {
            runtime.rememberKline(event.kline());
        }
        if (event.replay()) {
            return;
        }

        if (isFreshClosed15mKlineEvent(event, runtime.symbol.getSymbol(), localNow, exchangeNow,
            properties.getMarket().getMaxEventAgeMillis())
            && (runtime.lastClosedKlineOpenTime == null
                || event.kline().openTime().isAfter(runtime.lastClosedKlineOpenTime))) {
            ExchangeGateway.Kline kline = event.kline();
            BigDecimal referenceOpenPrice = runtime.referenceOpenPrice(kline);
            runtime.lastClosedKlineOpenTime = kline.openTime();
            if (referenceOpenPrice == null) {
                log.warn("双开策略参考K线未就绪或不连续，跳过本根信号 instanceId={} symbol={} klineOpenTime={}",
                    runtime.instance.getId(), runtime.symbol.getSymbol(), kline.openTime());
            } else {
                DualHedgeSlopeSignalEvaluator.evaluate("15m", kline, referenceOpenPrice, runtime.params)
                    .ifPresent(signal -> {
                        if (runtime.pendingSignal == null) {
                            runtime.pendingSignal = signal;
                        }
                    });
            }
        }

        if (fresh && runtime.pendingSignal != null) {
            persistPendingSignal(runtime, event.eventTime(), event.receivedAt(), localNow, exchangeNow);
        }
    }

    private void persistPendingSignal(DualHedgeRuntime runtime, Instant signalEventTime, Instant receivedAt,
                                      Instant localNow, Instant exchangeNow) {
        DualHedgeSlopeSignalEvaluator.Signal signal = runtime.pendingSignal;
        if (signal == null || runtime.closed) {
            return;
        }
        if (!MarketEventFreshness.isFresh(signalEventTime, receivedAt, localNow, exchangeNow,
            properties.getMarket().getMaxEventAgeMillis())) {
            runtime.pendingSignal = null;
            log.warn("双开信号超过有效窗口，放弃持久化 instanceId={} symbol={} klineOpenTime={} signalEventTime={}",
                runtime.instance.getId(), runtime.symbol.getSymbol(), signal.klineOpenTime(), signalEventTime);
            return;
        }
        try {
            if (hedgeRoundService.findBlockingRound(runtime.instance.getId(), runtime.symbol.getSymbol()) != null) {
                runtime.pendingSignal = null;
                return;
            }
            AccountBalanceSnapshot balance = balanceCache.requireTotalEquitySnapshot(runtime.credential.getId());
            BigDecimal equity = balance.totalEquity();
            BigDecimal marginRatio = DualHedgeSlopeSignalEvaluator.marginRatio(runtime.params);
            Integer leverage = runtime.strategySymbol.getLeverage() == null
                ? runtime.strategy.getDefaultLeverage() : runtime.strategySymbol.getLeverage();
            if (leverage == null || leverage <= 0) {
                throw new IllegalArgumentException("双开策略杠杆必须大于0");
            }
            BigDecimal marginPerLeg = equity.multiply(marginRatio);
            QuantStrategyHedgeRound round = new QuantStrategyHedgeRound();
            round.setStrategyId(runtime.instance.getStrategyId());
            round.setInstanceId(runtime.instance.getId());
            round.setCredentialId(runtime.credential.getId());
            round.setSymbol(runtime.symbol.getSymbol());
            round.setEntryKlineOpenTime(LocalDateTime.ofInstant(signal.klineOpenTime(), ZoneId.of("Asia/Shanghai")));
            round.setStatus(StrategyHedgeRoundService.STATUS_SIGNAL_READY);
            round.setEntryAngleDegrees(signal.angleDegrees());
            round.setReferencePrice(signal.closePrice());
            round.setSignalEventTime(LocalDateTime.ofInstant(signalEventTime, ZoneId.of("Asia/Shanghai")));
            round.setEntryEquitySnapshot(equity);
            round.setMarginRatio(marginRatio);
            round.setLeverage(leverage);
            round.setTargetMarginPerLeg(marginPerLeg);
            round.setTargetNotionalPerLeg(marginPerLeg.multiply(BigDecimal.valueOf(leverage)));
            round.setStrategyParamsSnapshot(QuantJsonMapper.writeMap(runtime.params));
            if (hedgeRoundService.claim(round)) {
                log.info("双开入场信号已持久化，等待执行阶段接管 instanceId={} symbol={} hedgeRoundKey={} klineOpenTime={} angle={} equity={} marginPerLeg={} notionalPerLeg={}",
                    runtime.instance.getId(), runtime.symbol.getSymbol(), round.getHedgeRoundKey(),
                    signal.klineOpenTime(), signal.angleDegrees(), equity, marginPerLeg,
                    round.getTargetNotionalPerLeg());
            } else {
                log.debug("双开入场信号已被数据库唯一约束拦截 instanceId={} symbol={} klineOpenTime={}",
                    runtime.instance.getId(), runtime.symbol.getSymbol(), signal.klineOpenTime());
            }
            runtime.pendingSignal = null;
        } catch (RuntimeException ex) {
            long now = System.currentTimeMillis();
            if (now >= runtime.nextSignalPersistLogAt) {
                runtime.nextSignalPersistLogAt = now + 10_000L;
                log.warn("双开入场信号暂未持久化，保留待处理信号等待账户权益或数据库恢复 instanceId={} symbol={} klineOpenTime={} errorType={}",
                    runtime.instance.getId(), runtime.symbol.getSymbol(), signal.klineOpenTime(),
                    ex.getClass().getSimpleName());
            }
        } catch (Exception ex) {
            log.error("双开入场信号参数快照序列化失败 instanceId={} symbol={} klineOpenTime={}",
                runtime.instance.getId(), runtime.symbol.getSymbol(), signal.klineOpenTime(), ex);
        }
    }

    static BigDecimal referenceOpenPrice(NavigableMap<Instant, ExchangeGateway.Kline> history,
                                         ExchangeGateway.Kline kline) {
        if (history == null || kline == null || kline.openTime() == null) {
            return null;
        }
        Map.Entry<Instant, ExchangeGateway.Kline> previous = history.lowerEntry(kline.openTime());
        if (previous == null) {
            return null;
        }
        ExchangeGateway.Kline reference = previous.getValue();
        if (!reference.closed() || reference.closeTime() == null
            || !reference.closeTime().plusMillis(1).equals(kline.openTime())
            || reference.open() == null || reference.open().signum() <= 0) {
            return null;
        }
        return reference.open();
    }

    static boolean isFreshClosed15mKlineEvent(ExchangeGateway.MarketEvent event, String expectedSymbol,
                                               Instant localNow, Instant exchangeNow, long maxAgeMillis) {
        return event != null && !event.replay() && event.kline() != null && event.kline().closed()
            && event.kline().openTime() != null && "15m".equalsIgnoreCase(event.interval()) && expectedSymbol != null
            && expectedSymbol.equalsIgnoreCase(event.symbol())
            && MarketEventFreshness.isFresh(event.eventTime(), event.receivedAt(), localNow, exchangeNow, maxAgeMillis);
    }

    /**
     * 双开实例不在本轮监督结果中时，关闭其订阅并释放租约。
     */
    public synchronized void retainOnly(Set<Long> activeInstanceIds) {
        for (Map.Entry<Long, DualHedgeRuntime> entry : runtimes.entrySet()) {
            if (activeInstanceIds == null || !activeInstanceIds.contains(entry.getKey())) {
                DualHedgeRuntime runtime = entry.getValue();
                if (runtimes.remove(entry.getKey(), runtime)) {
                    closeRuntime(runtime);
                }
            }
        }
    }

    public synchronized void stopInstance(Long instanceId) {
        if (instanceId == null) {
            return;
        }
        DualHedgeRuntime runtime = runtimes.remove(instanceId);
        closeRuntime(runtime);
    }

    public synchronized void invalidateCredential(Long credentialId) {
        closeCredentialRuntimes(credentialId);
    }

    public synchronized void setStrategySymbolRuntimeEnabled(Long strategySymbolId, boolean enabled) {
        if (strategySymbolId == null || enabled) {
            return;
        }
        for (Map.Entry<Long, DualHedgeRuntime> entry : runtimes.entrySet()) {
            DualHedgeRuntime runtime = entry.getValue();
            if (runtime.strategySymbol != null && strategySymbolId.equals(runtime.strategySymbol.getId())
                && runtimes.remove(entry.getKey(), runtime)) {
                closeRuntime(runtime);
            }
        }
    }

    public synchronized void disableCredential(Long credentialId) {
        closeCredentialRuntimes(credentialId);
    }

    private void closeCredentialRuntimes(Long credentialId) {
        if (credentialId == null) {
            return;
        }
        for (Map.Entry<Long, DualHedgeRuntime> entry : runtimes.entrySet()) {
            DualHedgeRuntime runtime = entry.getValue();
            if (Objects.equals(runtime.credential.getId(), credentialId) && runtimes.remove(entry.getKey(), runtime)) {
                closeRuntime(runtime);
            }
        }
    }

    /**
     * 周期核对凭证生命周期版本；只读取凭证 ID、版本和状态，不读取 API Key/Secret。
     */
    public synchronized void refreshCredentialVersions() {
        if (runtimes.isEmpty()) {
            return;
        }
        Set<Long> credentialIds = runtimes.values().stream()
            .map(runtime -> runtime.credential.getId()).collect(java.util.stream.Collectors.toSet());
        List<QuantCredential> credentials = credentialMapper.selectList(new LambdaQueryWrapper<QuantCredential>()
            .select(QuantCredential::getId, QuantCredential::getCredentialRuntimeVersion, QuantCredential::getStatus)
            .in(QuantCredential::getId, credentialIds));
        Map<Long, QuantCredential> latestById = new HashMap<>();
        credentials.forEach(credential -> latestById.put(credential.getId(), credential));
        for (Map.Entry<Long, DualHedgeRuntime> entry : runtimes.entrySet()) {
            DualHedgeRuntime runtime = entry.getValue();
            QuantCredential latest = latestById.get(runtime.credential.getId());
            if (latest == null || !"0".equals(latest.getStatus())
                || !Objects.equals(latest.getCredentialRuntimeVersion(), runtime.credential.getCredentialRuntimeVersion())) {
                if (runtimes.remove(entry.getKey(), runtime)) {
                    closeRuntime(runtime);
                }
            }
        }
    }

    /**
     * 双开 Runtime 使用与普通 Runtime 相同的数据库租约，但独立续期、独立失效。
     */
    public void renewActiveRuntimeLeases() {
        for (DualHedgeRuntime runtime : runtimes.values()) {
            if (runtime.closed || "PAPER".equalsIgnoreCase(runtime.credential.getRunMode())
                || !runtime.leaseRenewing.compareAndSet(false, true)) {
                continue;
            }
            try {
                leaseRenewalExecutor.execute(() -> {
                    try {
                        StrategyRuntimeLeaseService.LeaseSnapshot snapshot = null;
                        runtime.leaseOperationLock.lock();
                        try {
                            if (!runtime.closed) {
                                snapshot = leaseService.acquireOrRenewSnapshot(runtime.instance.getId(),
                                    runtime.credential.getId(), runtime.symbol.getSymbol(),
                                    runtime.credential.getCredentialRuntimeVersion());
                            }
                            if (!runtime.closed && snapshot != null) {
                                runtime.leaseSnapshot = snapshot;
                            }
                        } finally {
                            runtime.leaseOperationLock.unlock();
                        }
                        if (snapshot == null && !runtime.closed) {
                            log.error("双开策略运行租约续期未确认，保留原有效期并在到期后停止行情决策 instanceId={}",
                                runtime.instance.getId());
                        }
                    } finally {
                        runtime.leaseRenewing.set(false);
                    }
                });
            } catch (RuntimeException ex) {
                runtime.leaseRenewing.set(false);
                log.error("双开策略租约续期任务提交失败 instanceId={}", runtime.instance.getId(), ex);
            }
        }
    }

    public synchronized void stopAll() {
        for (DualHedgeRuntime runtime : runtimes.values()) {
            closeRuntime(runtime);
        }
        runtimes.clear();
    }

    private void closeRuntime(DualHedgeRuntime runtime) {
        if (runtime == null) {
            return;
        }
        runtime.closed = true;
        if (runtime.subscription != null) {
            try {
                runtime.subscription.close();
            } catch (Exception ex) {
                log.warn("关闭双开策略行情订阅失败 instanceId={}", runtime.instance.getId(), ex);
            }
            runtime.subscription = null;
        }
        if (runtime.userSubscription != null) {
            try {
                runtime.userSubscription.close();
            } catch (Exception ex) {
                log.warn("关闭双开策略用户数据订阅失败 instanceId={}", runtime.instance.getId(), ex);
            }
            runtime.userSubscription = null;
        }
        boolean acquired = false;
        try {
            acquired = runtime.leaseOperationLock.tryLock(2, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        if (!acquired) {
            runtime.leaseSnapshot = null;
            log.error("双开策略租约操作仍在途，停止释放并等待租约自然过期 instanceId={}",
                runtime.instance.getId());
            return;
        }
        try {
            leaseService.release(runtime.instance.getId(), runtime.credential.getId(), runtime.symbol.getSymbol());
            runtime.leaseSnapshot = null;
        } finally {
            runtime.leaseOperationLock.unlock();
        }
    }

    private static boolean isLeaseSnapshotValid(StrategyRuntimeLeaseService.LeaseSnapshot snapshot) {
        return snapshot != null && snapshot.epoch() > 0 && System.nanoTime() - snapshot.validUntilNanos() < 0;
    }

    @PreDestroy
    public void destroy() {
        stopAll();
        leaseRenewalExecutor.shutdownNow();
    }

    private static final class DualHedgeRuntime {
        private final QuantStrategyInstance instance;
        private volatile QuantStrategy strategy;
        private volatile QuantStrategySymbol strategySymbol;
        private volatile QuantCredential credential;
        private final QuantSymbol symbol;
        private volatile StrategyRuntimeLeaseService.LeaseSnapshot leaseSnapshot;
        private volatile AutoCloseable subscription;
        private volatile AutoCloseable userSubscription;
        private final AtomicBoolean leaseRenewing = new AtomicBoolean();
        private final ReentrantLock leaseOperationLock = new ReentrantLock();
        private volatile boolean marketStreamReady;
        private volatile String marketStreamStatus = "CONNECTING";
        private volatile Instant lastClosedKlineOpenTime;
        private volatile Map<String, Object> params;
        private final NavigableMap<Instant, ExchangeGateway.Kline> klineHistory = new TreeMap<>();
        private volatile DualHedgeSlopeSignalEvaluator.Signal pendingSignal;
        private volatile long nextSignalPersistLogAt;
        private volatile boolean closed;

        private DualHedgeRuntime(QuantStrategyInstance instance, QuantStrategy strategy, QuantStrategySymbol strategySymbol,
                                 QuantCredential credential, QuantSymbol symbol,
                                 StrategyRuntimeLeaseService.LeaseSnapshot leaseSnapshot, Map<String, Object> params) {
            this.instance = instance;
            this.strategy = strategy;
            this.strategySymbol = strategySymbol;
            this.credential = credential;
            this.symbol = symbol;
            this.leaseSnapshot = leaseSnapshot;
            this.params = params;
        }

        private synchronized void rememberKline(ExchangeGateway.Kline kline) {
            if (kline == null || kline.openTime() == null) {
                return;
            }
            klineHistory.put(kline.openTime(), kline);
            while (klineHistory.size() > 8) {
                klineHistory.pollFirstEntry();
            }
        }

        private synchronized BigDecimal referenceOpenPrice(ExchangeGateway.Kline kline) {
            return DualHedgeRuntimeService.referenceOpenPrice(klineHistory, kline);
        }

        private boolean matches(QuantCredential credential, String symbol, String interval) {
            return !closed && Objects.equals(this.credential.getId(), credential.getId())
                && Objects.equals(this.credential.getCredentialRuntimeVersion(), credential.getCredentialRuntimeVersion())
                && this.symbol.getSymbol().equalsIgnoreCase(symbol)
                && Objects.equals(this.strategySymbol.getIntervalCode(), interval)
                && Objects.equals(this.credential.getRunMode(), credential.getRunMode());
        }
    }
}
