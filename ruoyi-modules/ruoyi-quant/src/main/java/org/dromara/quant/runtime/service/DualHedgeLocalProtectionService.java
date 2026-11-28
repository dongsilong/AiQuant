package org.dromara.quant.runtime.service;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.quant.config.QuantProperties;
import org.dromara.quant.credential.domain.QuantCredential;
import org.dromara.quant.credential.mapper.QuantCredentialMapper;
import org.dromara.quant.exchange.AccountPositionSnapshotQuery;
import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.exchange.ExchangeTradingRuleCache;
import org.dromara.quant.exchange.QuantityNormalizer;
import org.dromara.quant.execution.ExecutionRecordService;
import org.dromara.quant.execution.ExecutionResult;
import org.dromara.quant.execution.TradeExecutionApplicationService;
import org.dromara.quant.execution.domain.QuantExecutionRecord;
import org.dromara.quant.market.MarketEventFreshness;
import org.dromara.quant.market.MarketStreamHub;
import org.dromara.quant.position.domain.QuantPosition;
import org.dromara.quant.position.mapper.QuantPositionMapper;
import org.dromara.quant.protection.ProtectionOrderService;
import org.dromara.quant.runtime.DualHedgeLocalProtectionEvaluator;
import org.dromara.quant.runtime.PositionProtectionState;
import org.dromara.quant.runtime.coordination.StrategyRuntimeLeaseService;
import org.dromara.quant.runtime.domain.QuantStrategyPositionState;
import org.dromara.quant.strategy.domain.QuantStrategyInstance;
import org.dromara.quant.strategy.mapper.QuantStrategyInstanceMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 双开 LONG/SHORT 独立本地保护执行器。
 *
 * <p>价格 ROI 与 Binance MARK_PRICE 条件单使用相同的杠杆回报率口径。发送本地 CLOSE 前，
 * 必须先精确核实并撤销对应 Binance 保护，且本地 CLOSE 的幂等身份先于远端请求持久化。</p>
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class DualHedgeLocalProtectionService {
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final long POSITION_SNAPSHOT_CACHE_MILLIS = 1000L;

    private final StrategyPositionStateService stateService;
    private final QuantPositionMapper positionMapper;
    private final QuantCredentialMapper credentialMapper;
    private final QuantStrategyInstanceMapper instanceMapper;
    private final StrategyRuntimeLeaseService leaseService;
    private final MarketStreamHub marketStreamHub;
    private final ExchangeGateway gateway;
    private final QuantProperties properties;
    private final ExchangeTradingRuleCache tradingRuleCache;
    private final TradeExecutionApplicationService executionService;
    private final ExecutionRecordService executionRecordService;
    private final AccountPositionSnapshotQuery positionSnapshotQuery;
    private final ProtectionOrderService protectionOrderService;
    private final StrategyHedgeRoundService roundService;

    private final Map<String, LocalRuntime> runtimes = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> cycleLocks = new ConcurrentHashMap<>();
    private final Map<String, SnapshotCacheEntry> positionSnapshots = new ConcurrentHashMap<>();
    private final ExecutorService exitExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 为仍有双开敞口的持仓建立独立行情订阅，并恢复未确认的本地退出动作。
     * 即使策略实例已停止，已有双开持仓仍继续保留本地保护。
     */
    @Scheduled(fixedDelay = 2000L, initialDelay = 2000L)
    public void reconcile() {
        List<QuantStrategyPositionState> states = stateService.findUnclosed().stream()
            .filter(DualHedgeLocalProtectionService::isDualLeg)
            .toList();
        Map<String, QuantStrategyPositionState> desired = new HashMap<>();
        for (QuantStrategyPositionState state : states) {
            String key = state.getPositionCycleKey();
            if (key == null || key.isBlank()) {
                continue;
            }
            desired.put(key, state);
            try {
                LocalRuntime runtime = ensureRuntime(state);
                if (runtime == null) {
                    continue;
                }
                if (state.getPendingAction() != null) {
                    reconcilePending(state, runtime);
                } else if ("OPEN".equalsIgnoreCase(state.getStatus())) {
                    reconcileExchangePositionChange(state, runtime);
                }
            } catch (RuntimeException ex) {
                log.warn("双开本地保护恢复暂未完成 instanceId={} leg={} cycle={} errorType={}",
                    state.getInstanceId(), state.getLegKey(), state.getPositionCycleKey(), ex.getClass().getSimpleName());
            }
        }
        runtimes.forEach((key, runtime) -> {
            if (!desired.containsKey(key) && runtimes.remove(key, runtime)) {
                closeSubscription(runtime);
            }
        });
    }

    private LocalRuntime ensureRuntime(QuantStrategyPositionState state) {
        QuantPosition position = positionMapper.selectById(state.getPositionId());
        QuantStrategyInstance instance = instanceMapper.selectById(state.getInstanceId());
        if (position == null || instance == null || instance.getCredentialId() == null
            || !state.getPositionId().equals(position.getId())
            || state.getDirection() == null || !state.getDirection().equalsIgnoreCase(position.getPositionSide())) {
            return null;
        }
        QuantCredential credential = credentialMapper.selectById(position.getCredentialId());
        if (credential == null || !"0".equals(credential.getStatus())
            || credential.getCredentialRuntimeVersion() == null
            || !instance.getCredentialId().equals(credential.getId())
            || "PAPER".equalsIgnoreCase(credential.getRunMode())) {
            return null;
        }
        String key = state.getPositionCycleKey();
        LocalRuntime current = runtimes.get(key);
        StrategyRuntimeLeaseService.LeaseSnapshot lease =
            leaseService.acquireOrRenewSnapshot(instance.getId(), credential.getId(), position.getSymbol(),
                credential.getCredentialRuntimeVersion());
        if (lease == null) {
            if (current != null && runtimes.remove(key, current)) {
                closeSubscription(current);
            }
            return null;
        }
        if (current != null && current.matches(credential, position.getSymbol())) {
            current.leaseSnapshot = lease;
            current.credential = credential;
            current.instance = instance;
            return current;
        }
        if (current != null) {
            closeSubscription(current);
        }
        LocalRuntime runtime = new LocalRuntime(key, credential, instance, position.getSymbol(), lease);
        runtime.subscription = marketStreamHub.subscribe(credential.getRunMode(), position.getSymbol(), "15m",
            event -> onMarketEvent(runtime, event), ignored -> { });
        runtimes.put(key, runtime);
        return runtime;
    }

    private void onMarketEvent(LocalRuntime runtime, ExchangeGateway.MarketEvent event) {
        if (runtime.closed || event == null || event.replay() || !"MARK".equals(event.type())
            || event.price() == null || event.eventTime() == null || event.receivedAt() == null
            || event.symbol() == null || !event.symbol().equalsIgnoreCase(runtime.symbol)) {
            return;
        }
        Instant localNow = Instant.now();
        Instant exchangeNow = localNow.plusMillis(gateway.exchangeTimeOffsetMillis(runtime.credential.getRunMode()));
        if (!MarketEventFreshness.isFresh(event.eventTime(), event.receivedAt(), localNow, exchangeNow,
            properties.getMarket().getMaxEventAgeMillis())) {
            return;
        }
        if (!runtime.evaluating.compareAndSet(false, true)) {
            return;
        }
        exitExecutor.execute(() -> {
            try {
                evaluateLeg(runtime, event.price(), event.eventTime());
            } catch (RuntimeException ex) {
                log.warn("双开本地保护检查暂未完成 instanceId={} symbol={} cycle={} errorType={}",
                    runtime.instance.getId(), runtime.symbol, runtime.cycleKey, ex.getClass().getSimpleName());
            } finally {
                runtime.evaluating.set(false);
            }
        });
    }

    private void evaluateLeg(LocalRuntime runtime, BigDecimal markPrice, Instant eventTime) {
        leaseService.requireCurrentOwner(runtime.instance.getId(), runtime.credential.getId(),
            runtime.symbol, runtime.credential.getCredentialRuntimeVersion());
        QuantStrategyPositionState state = stateService.findByInstanceAndLeg(
            runtime.instance.getId(), legFromCycle(runtime.cycleKey));
        if (!isOpen(state) || !runtime.cycleKey.equals(state.getPositionCycleKey()) || state.getPendingAction() != null) {
            return;
        }
        QuantPosition position = positionMapper.selectById(state.getPositionId());
        if (!matchesPosition(state, position) || state.getEntryPrice() == null
            || state.getFrozenLeverage() == null || state.getFrozenLeverage() <= 0) {
            return;
        }

        BigDecimal currentRoi = org.dromara.quant.strategy.KlineSlopeRateStrategy.leveragedReturn(
            state.getDirection(), markPrice, state.getEntryPrice(), state.getFrozenLeverage());
        BigDecimal previousMaximum = state.getMaximumReturnRate() == null ? ZERO : state.getMaximumReturnRate();
        BigDecimal maximum = previousMaximum.max(currentRoi);
        if (maximum.compareTo(previousMaximum) > 0) {
            if (stateService.updateMaximumReturnRate(state.getInstanceId(), state.getPositionId(),
                state.getPositionCycleKey(), maximum) != 1) {
                return;
            }
            state.setMaximumReturnRate(maximum);
        }

        if (!Boolean.TRUE.equals(state.getFirstTakeProfitTriggered())
            && (maximum.compareTo(new BigDecimal("0.05")) >= 0 || currentRoi.signum() <= 0)) {
            refreshFirstTakeProfitFacts(state, runtime, position);
        }

        DualHedgeLocalProtectionEvaluator.Decision decision = DualHedgeLocalProtectionEvaluator.evaluate(
            state.getDirection(), markPrice, state.getEntryPrice(), state.getFrozenLeverage(),
            state.getMaximumReturnRate() == null ? ZERO : state.getMaximumReturnRate(),
            Boolean.TRUE.equals(state.getFirstTakeProfitTriggered()));

        boolean stateChanged = false;
        if (state.getHighestStep() == null || decision.stepIndex() > state.getHighestStep()) {
            state.setHighestStep(decision.stepIndex());
            stateChanged = true;
        }
        if (!decimalEquals(state.getTrailingStopRoi(), decision.lockRoi())) {
            state.setTrailingStopRoi(decision.lockRoi());
            stateChanged = true;
        }
        if (decision.stepIndex() > 0 && !Boolean.TRUE.equals(state.getTrailingArmed())) {
            state.setTrailingArmed(true);
            stateChanged = true;
        }
        if (stateChanged) {
            stateService.save(state);
        }

        if (decision.action() != DualHedgeLocalProtectionEvaluator.Action.NONE) {
            executeLocalExit(runtime, state, decision, markPrice, eventTime);
        }
    }

    private void refreshFirstTakeProfitFacts(QuantStrategyPositionState state, LocalRuntime runtime,
                                             QuantPosition position) {
        ExchangeGateway.Credential credential = executionService.toCredential(runtime.credential);
        ExchangeGateway.SymbolRule rule = tradingRuleCache.getForProtection(
            runtime.credential.getRunMode(), credential, position.getSymbol());
        if (rule == null) {
            throw new IllegalStateException("交易所数量规则不可用，不能核实首次止盈数量");
        }
        BigDecimal target = QuantityNormalizer.quantity(
            state.getInitialPositionQty().multiply(new BigDecimal("0.50")), rule, "MARKET");
        if (state.getFirstTakeProfitTargetQty() == null) {
            state.setFirstTakeProfitTargetQty(target);
        } else if (state.getFirstTakeProfitTargetQty().compareTo(target) != 0) {
            throw new IllegalStateException("双开首次止盈目标数量与冻结初始仓位不一致");
        }
        BigDecimal exchangeExecuted = protectionOrderService.firstTakeProfitExecutedQuantity(
            runtime.credential.getId(), withProtectionCycle(position, state));
        PositionProtectionState.confirmExchangeFirstProfit(state, exchangeExecuted);
        stateService.save(state);
    }

    private void executeLocalExit(LocalRuntime runtime, QuantStrategyPositionState state,
                                  DualHedgeLocalProtectionEvaluator.Decision decision,
                                  BigDecimal markPrice, Instant eventTime) {
        ReentrantLock lock = cycleLocks.computeIfAbsent(runtime.cycleKey, ignored -> new ReentrantLock());
        if (!lock.tryLock()) {
            return;
        }
        try {
            leaseService.requireCurrentOwner(runtime.instance.getId(), runtime.credential.getId(),
                runtime.symbol, runtime.credential.getCredentialRuntimeVersion());
            QuantStrategyPositionState latest = stateService.findByInstanceAndLeg(
                runtime.instance.getId(), state.getLegKey());
            if (!isOpen(latest) || latest.getPendingAction() != null
                || !runtime.cycleKey.equals(latest.getPositionCycleKey())) {
                return;
            }
            QuantPosition currentPosition = positionMapper.selectById(latest.getPositionId());
            if (!matchesPosition(latest, currentPosition)) {
                return;
            }
            boolean partialFirstProfit = decision.action() == DualHedgeLocalProtectionEvaluator.Action.FIRST_TAKE_PROFIT;
            QuantPosition scopedPosition = withProtectionCycle(currentPosition, latest);
            boolean protectionsReady = partialFirstProfit
                ? protectionOrderService.prepareLocalExit(runtime.credential.getId(), scopedPosition, "FIRST_TP")
                : protectionOrderService.quiesceCycleProtections(runtime.credential.getId(), scopedPosition);
            if (!protectionsReady) {
                latest.setRecoveryRequired(true);
                stateService.save(latest);
                return;
            }

            BigDecimal exchangeQuantity = currentPositionQuantity(runtime, currentPosition);
            if (exchangeQuantity.compareTo(latest.getPositionQty()) != 0) {
                if (partialFirstProfit) {
                    BigDecimal knownExchangeExecuted = zero(latest.getFirstTakeProfitExecutedQty())
                        .subtract(zero(latest.getFirstTakeProfitLocalExecutedQty())).max(ZERO);
                    refreshFirstTakeProfitFacts(latest, runtime, currentPosition);
                    BigDecimal nowExchangeExecuted = zero(latest.getFirstTakeProfitExecutedQty())
                        .subtract(zero(latest.getFirstTakeProfitLocalExecutedQty())).max(ZERO);
                    BigDecimal expectedPosition = latest.getPositionQty()
                        .subtract(nowExchangeExecuted.subtract(knownExchangeExecuted).max(ZERO)).max(ZERO);
                    if (exchangeQuantity.compareTo(expectedPosition) == 0) {
                        positionStateServiceSavePosition(latest, currentPosition, exchangeQuantity);
                        return;
                    }
                }
                latest.setRecoveryRequired(true);
                latest.setUnsafeRecovery(true);
                stateService.save(latest);
                return;
            }

            BigDecimal targetQuantity = partialFirstProfit
                ? PositionProtectionState.remainingFirstProfit(latest) : exchangeQuantity;
            ExchangeGateway.Credential credential = executionService.toCredential(runtime.credential);
            ExchangeGateway.SymbolRule rule = tradingRuleCache.get(runtime.credential.getRunMode(), credential, runtime.symbol);
            targetQuantity = QuantityNormalizer.quantity(targetQuantity, rule, "MARKET");
            if (targetQuantity.signum() <= 0 || targetQuantity.compareTo(exchangeQuantity) > 0) {
                throw new IllegalStateException("本地退出数量无效，拒绝发送 CLOSE");
            }
            QuantityNormalizer.requireNotional(markPrice, targetQuantity, rule);
            if (!partialFirstProfit && targetQuantity.compareTo(exchangeQuantity) != 0) {
                throw new IllegalStateException("整仓保护退出数量未覆盖全部剩余持仓");
            }

            String action = decision.action().name();
            String idempotencyKey = "DUAL_HEDGE:" + latest.getPositionCycleKey() + ":EXIT:"
                + action + ":" + eventTime.toEpochMilli();
            latest.setStatus("EXIT_PENDING");
            latest.setPendingAction(action);
            latest.setPendingIdempotencyKey(idempotencyKey);
            latest.setPendingOrderTime(System.currentTimeMillis());
            latest.setPendingOrderId(null);
            latest.setPendingClientOrderId(null);
            latest.setPendingTargetQuantity(targetQuantity);
            latest.setPendingNotFoundConfirmations(0);
            latest.setRecoveryRequired(true);
            latest.setUnsafeRecovery(true);
            latest.setExchangeProtectionReady(false);
            stateService.save(latest);

            String side = "SHORT".equalsIgnoreCase(latest.getDirection()) ? "BUY" : "SELL";
            try {
                ExecutionResult result = executionService.closeByRuntime(runtime.credential.getId(),
                    runtime.instance.getStrategyId(), runtime.instance.getId(), runtime.symbol, side,
                    latest.getDirection(), targetQuantity, idempotencyKey,
                    runtime.credential.getCredentialRuntimeVersion());
                if (result != null) {
                    latest.setPendingOrderId(result.orderId());
                    latest.setPendingClientOrderId(result.clientOrderId());
                    stateService.save(latest);
                }
            } catch (RuntimeException ex) {
                log.warn("双开本地 CLOSE 已提交或结果未知，保留原幂等身份等待核实 instanceId={} leg={} cycle={} action={}",
                    latest.getInstanceId(), latest.getLegKey(), latest.getPositionCycleKey(), action, ex);
            }
            reconcilePending(latest, runtime);
        } finally {
            lock.unlock();
        }
    }

    private void reconcilePending(QuantStrategyPositionState state, LocalRuntime runtime) {
        ReentrantLock lock = cycleLocks.computeIfAbsent(runtime.cycleKey, ignored -> new ReentrantLock());
        if (!lock.tryLock()) {
            return;
        }
        try {
            String key = state.getPendingIdempotencyKey();
            if (key == null || key.isBlank() || state.getPendingTargetQuantity() == null
                || state.getPendingTargetQuantity().signum() <= 0) {
                return;
            }
            QuantExecutionRecord execution = executionRecordService.find(runtime.credential.getId(), key);
            if (execution == null) {
                // Crash before the execution record was durable: never guess whether the remote call started.
                return;
            }
            if ("FAILED".equalsIgnoreCase(execution.getExecutionStatus())) {
                restoreAfterExplicitFailure(state, runtime);
                return;
            }
            if (!"CLOSE".equalsIgnoreCase(execution.getOperationType())
                || !runtime.symbol.equalsIgnoreCase(execution.getSymbol())
                || !runtime.instance.getId().equals(execution.getStrategyInstanceId())
                || !runtime.instance.getStrategyId().equals(execution.getStrategyId())
                || !Set.of("PENDING", "UNKNOWN", "CONFIRMED", "RECOVERED").contains(
                    java.util.Objects.toString(execution.getExecutionStatus(), "").toUpperCase(java.util.Locale.ROOT))) {
                return;
            }
            Long orderId = execution.getExchangeOrderId();
            String clientOrderId = execution.getClientOrderId();
            ExchangeGateway.OrderSnapshot order = gateway.queryOrder(
                executionService.toCredential(runtime.credential), runtime.symbol, orderId, clientOrderId);
            if (order == null) {
                return;
            }
            String expectedSide = "SHORT".equalsIgnoreCase(state.getDirection()) ? "BUY" : "SELL";
            if (!runtime.symbol.equalsIgnoreCase(order.symbol())
                || !state.getDirection().equalsIgnoreCase(order.positionSide())
                || !expectedSide.equalsIgnoreCase(order.side())
                || orderId != null && !orderId.equals(order.orderId())
                || order.executedQuantity() == null
                || order.executedQuantity().compareTo(state.getPendingTargetQuantity()) > 0) {
                state.setRecoveryRequired(true);
                state.setUnsafeRecovery(true);
                stateService.save(state);
                return;
            }
            if ("NEW".equalsIgnoreCase(order.status()) || "PARTIALLY_FILLED".equalsIgnoreCase(order.status())) {
                return;
            }
            if (Set.of("CANCELED", "EXPIRED", "REJECTED").contains(
                java.util.Objects.toString(order.status(), "").toUpperCase(java.util.Locale.ROOT))
                && order.executedQuantity().signum() == 0) {
                restoreAfterExplicitFailure(state, runtime);
                return;
            }
            if (!"FILLED".equalsIgnoreCase(order.status())
                || order.executedQuantity().compareTo(state.getPendingTargetQuantity()) != 0
                || order.originalQuantity() == null
                || order.originalQuantity().compareTo(state.getPendingTargetQuantity()) != 0) {
                state.setRecoveryRequired(true);
                state.setUnsafeRecovery(true);
                stateService.save(state);
                return;
            }
            if ("PENDING".equalsIgnoreCase(execution.getExecutionStatus())
                || "UNKNOWN".equalsIgnoreCase(execution.getExecutionStatus())) {
                executionRecordService.markRecovered(execution, order.orderId(), order.status());
            }
            positionSnapshots.remove(runtime.credential.getId() + ":" + runtime.credential.getRunMode());
            List<ExchangeGateway.PositionSnapshot> snapshots =
                positionSnapshotQuery.query(runtime.credential.getId(), executionService.toCredential(runtime.credential));
            BigDecimal expectedRemaining = state.getPositionQty().subtract(order.executedQuantity()).max(ZERO);
            BigDecimal actualRemaining = findPositionQuantity(snapshots, runtime.symbol, state.getDirection());
            if (actualRemaining.compareTo(expectedRemaining) != 0) {
                state.setRecoveryRequired(true);
                state.setUnsafeRecovery(true);
                stateService.save(state);
                return;
            }
            QuantPosition position = positionMapper.selectById(state.getPositionId());
            if (position == null) {
                state.setRecoveryRequired(true);
                state.setUnsafeRecovery(true);
                stateService.save(state);
                return;
            }
            updatePositionFromSnapshot(position, snapshots, actualRemaining);
            positionMapper.updateById(position);
            if (actualRemaining.signum() == 0) {
                stateService.markClosed(state);
                roundService.markLegClosed(state.getHedgeRoundKey(), state.getLegKey());
                return;
            }
            confirmPartialExit(state, actualRemaining, order.executedQuantity());
        } finally {
            lock.unlock();
        }
    }

    private void restoreAfterExplicitFailure(QuantStrategyPositionState state, LocalRuntime runtime) {
        positionSnapshots.remove(runtime.credential.getId() + ":" + runtime.credential.getRunMode());
        List<ExchangeGateway.PositionSnapshot> snapshots =
            positionSnapshotQuery.query(runtime.credential.getId(), executionService.toCredential(runtime.credential));
        BigDecimal actual = findPositionQuantity(snapshots, runtime.symbol, state.getDirection());
        if (actual.compareTo(state.getPositionQty()) != 0) {
            state.setRecoveryRequired(true);
            state.setUnsafeRecovery(true);
            stateService.save(state);
            return;
        }
        clearPending(state);
        state.setStatus("OPEN");
        state.setRecoveryRequired(true);
        state.setUnsafeRecovery(false);
        state.setExchangeProtectionReady(false);
        stateService.save(state);
    }

    private void confirmPartialExit(QuantStrategyPositionState state, BigDecimal remaining, BigDecimal executed) {
        if ("FIRST_TAKE_PROFIT".equals(state.getPendingAction())) {
            PositionProtectionState.confirmLocalFirstProfit(state, executed);
        }
        state.setPositionQty(remaining);
        state.setStatus("OPEN");
        state.setRecoveryRequired(true);
        state.setUnsafeRecovery(false);
        state.setExchangeProtectionReady(false);
        clearPending(state);
        stateService.save(state);
    }

    private void reconcileExchangePositionChange(QuantStrategyPositionState state, LocalRuntime runtime) {
        QuantPosition position = positionMapper.selectById(state.getPositionId());
        if (!matchesPosition(state, position)) {
            return;
        }
        List<ExchangeGateway.PositionSnapshot> snapshots =
            queryPositionSnapshot(runtime.credential.getId(), executionService.toCredential(runtime.credential));
        BigDecimal actual = findPositionQuantity(snapshots, runtime.symbol, state.getDirection());
        if (actual.compareTo(state.getPositionQty()) == 0) {
            return;
        }
        if (actual.signum() == 0) {
            if (!protectionOrderService.cancelActiveProtectionOrders(runtime.credential.getId(),
                state.getPositionId(), state.getPositionCycleKey(), true)) {
                state.setRecoveryRequired(true);
                state.setUnsafeRecovery(true);
                stateService.save(state);
                return;
            }
            position.setQuantity(ZERO);
            position.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
            positionMapper.updateById(position);
            stateService.markClosed(state);
            roundService.markLegClosed(state.getHedgeRoundKey(), state.getLegKey());
            return;
        }
        if (actual.compareTo(state.getPositionQty()) < 0) {
            BigDecimal previousExchangeExecuted = zero(state.getFirstTakeProfitExecutedQty())
                .subtract(zero(state.getFirstTakeProfitLocalExecutedQty())).max(ZERO);
            refreshFirstTakeProfitFacts(state, runtime, position);
            BigDecimal nowExchangeExecuted = zero(state.getFirstTakeProfitExecutedQty())
                .subtract(zero(state.getFirstTakeProfitLocalExecutedQty())).max(ZERO);
            BigDecimal expected = state.getPositionQty().subtract(nowExchangeExecuted
                .subtract(previousExchangeExecuted).max(ZERO)).max(ZERO);
            if (actual.compareTo(expected) == 0) {
                state.setPositionQty(actual);
                state.setRecoveryRequired(true);
                state.setUnsafeRecovery(false);
                state.setExchangeProtectionReady(false);
                updatePositionFromSnapshot(position, snapshots, actual);
                positionMapper.updateById(position);
                stateService.save(state);
                return;
            }
        }
        state.setRecoveryRequired(true);
        state.setUnsafeRecovery(true);
        stateService.save(state);
    }

    private BigDecimal currentPositionQuantity(LocalRuntime runtime, QuantPosition position) {
        positionSnapshots.remove(runtime.credential.getId() + ":" + runtime.credential.getRunMode());
        List<ExchangeGateway.PositionSnapshot> snapshots =
            queryPositionSnapshot(runtime.credential.getId(), executionService.toCredential(runtime.credential));
        return findPositionQuantity(snapshots, runtime.symbol, position.getPositionSide());
    }

    private List<ExchangeGateway.PositionSnapshot> queryPositionSnapshot(Long credentialId,
                                                                          ExchangeGateway.Credential credential) {
        String key = credentialId + ":" + credential.runMode();
        long now = System.currentTimeMillis();
        SnapshotCacheEntry cached = positionSnapshots.get(key);
        if (cached != null && now - cached.checkedAtMillis < POSITION_SNAPSHOT_CACHE_MILLIS) {
            return cached.positions;
        }
        List<ExchangeGateway.PositionSnapshot> fresh = positionSnapshotQuery.query(credentialId, credential);
        positionSnapshots.put(key, new SnapshotCacheEntry(fresh, now));
        return fresh;
    }

    private static BigDecimal findPositionQuantity(List<ExchangeGateway.PositionSnapshot> snapshots,
                                                   String symbol, String direction) {
        List<ExchangeGateway.PositionSnapshot> matches = snapshots.stream()
            .filter(row -> symbol.equalsIgnoreCase(row.symbol()) && direction.equalsIgnoreCase(row.side())
                && row.quantity() != null && row.quantity().signum() != 0).toList();
        if (matches.size() > 1) {
            throw new IllegalStateException("双开持仓快照存在重复方向记录，停止本地退出");
        }
        return matches.isEmpty() ? ZERO : matches.getFirst().quantity().abs();
    }

    private static void updatePositionFromSnapshot(QuantPosition position,
                                                   List<ExchangeGateway.PositionSnapshot> snapshots,
                                                   BigDecimal quantity) {
        ExchangeGateway.PositionSnapshot match = snapshots.stream()
            .filter(row -> position.getSymbol().equalsIgnoreCase(row.symbol())
                && position.getPositionSide().equalsIgnoreCase(row.side())
                && row.quantity() != null && row.quantity().signum() != 0)
            .findFirst().orElse(null);
        position.setQuantity(quantity);
        if (match != null) {
            position.setEntryPrice(match.entryPrice());
            position.setMarkPrice(match.markPrice());
            position.setUnrealizedPnl(match.unrealizedPnl());
            position.setMargin(match.margin());
            position.setLeverage(match.leverage());
        }
        position.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
    }

    private void positionStateServiceSavePosition(QuantStrategyPositionState state, QuantPosition position,
                                                   BigDecimal quantity) {
        state.setPositionQty(quantity);
        state.setStatus("OPEN");
        state.setRecoveryRequired(true);
        state.setUnsafeRecovery(false);
        state.setExchangeProtectionReady(false);
        clearPending(state);
        stateService.save(state);
        position.setQuantity(quantity);
        position.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        positionMapper.updateById(position);
    }

    private static boolean isDualLeg(QuantStrategyPositionState state) {
        return state != null && state.getInstanceId() != null && state.getPositionId() != null
            && state.getHedgeRoundKey() != null && state.getPositionCycleKey() != null
            && Set.of("LONG", "SHORT").contains(state.getLegKey())
            && state.getPositionQty() != null && state.getPositionQty().signum() > 0
            && !"CLOSED".equalsIgnoreCase(state.getStatus());
    }

    private static boolean isOpen(QuantStrategyPositionState state) {
        return isDualLeg(state) && "OPEN".equalsIgnoreCase(state.getStatus());
    }

    private static boolean matchesPosition(QuantStrategyPositionState state, QuantPosition position) {
        return position != null && state.getPositionId().equals(position.getId())
            && state.getDirection() != null && state.getDirection().equalsIgnoreCase(position.getPositionSide())
            && state.getPositionQty() != null && position.getQuantity() != null
            && state.getPositionQty().compareTo(position.getQuantity()) == 0
            && state.getEntryPrice() != null && position.getEntryPrice() != null
            && state.getEntryPrice().compareTo(position.getEntryPrice()) == 0;
    }

    private static QuantPosition withProtectionCycle(QuantPosition position, QuantStrategyPositionState state) {
        position.setProtectionCycleKey(state.getPositionCycleKey());
        position.setProtectionPolicyVersion(3);
        return position;
    }

    private static void clearPending(QuantStrategyPositionState state) {
        state.setPendingAction(null);
        state.setPendingIdempotencyKey(null);
        state.setPendingOrderId(null);
        state.setPendingClientOrderId(null);
        state.setPendingOrderTime(null);
        state.setPendingTargetQuantity(null);
        state.setPendingNotFoundConfirmations(0);
    }

    private static BigDecimal zero(BigDecimal value) {
        return value == null ? ZERO : value;
    }

    private static String legFromCycle(String cycleKey) {
        return cycleKey.endsWith(":SHORT") ? "SHORT" : "LONG";
    }

    private static boolean decimalEquals(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }

    private void closeSubscription(LocalRuntime runtime) {
        runtime.closed = true;
        if (runtime.subscription != null) {
            try {
                runtime.subscription.close();
            } catch (Exception ex) {
                log.warn("关闭双开本地保护行情订阅失败 cycle={}", runtime.cycleKey, ex);
            }
        }
    }

    @PreDestroy
    public void destroy() {
        runtimes.values().forEach(this::closeSubscription);
        runtimes.clear();
        exitExecutor.shutdownNow();
    }

    private static final class LocalRuntime {
        private final String cycleKey;
        private volatile QuantCredential credential;
        private volatile QuantStrategyInstance instance;
        private final String symbol;
        private volatile StrategyRuntimeLeaseService.LeaseSnapshot leaseSnapshot;
        private volatile AutoCloseable subscription;
        private final AtomicBoolean evaluating = new AtomicBoolean();
        private volatile boolean closed;

        private LocalRuntime(String cycleKey, QuantCredential credential, QuantStrategyInstance instance,
                             String symbol, StrategyRuntimeLeaseService.LeaseSnapshot leaseSnapshot) {
            this.cycleKey = cycleKey;
            this.credential = credential;
            this.instance = instance;
            this.symbol = symbol;
            this.leaseSnapshot = leaseSnapshot;
        }

        private boolean matches(QuantCredential credential, String symbol) {
            return !closed && this.credential.getId().equals(credential.getId())
                && java.util.Objects.equals(this.credential.getCredentialRuntimeVersion(), credential.getCredentialRuntimeVersion())
                && this.symbol.equalsIgnoreCase(symbol);
        }
    }

    private record SnapshotCacheEntry(List<ExchangeGateway.PositionSnapshot> positions, long checkedAtMillis) {
    }
}
