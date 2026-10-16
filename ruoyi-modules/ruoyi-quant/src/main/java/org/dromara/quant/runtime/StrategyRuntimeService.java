package org.dromara.quant.runtime;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.dromara.quant.config.QuantProperties;
import org.dromara.quant.credential.domain.QuantCredential;
import org.dromara.quant.credential.mapper.QuantCredentialMapper;
import org.dromara.quant.decision.DecisionLogCursor;
import org.dromara.quant.decision.DecisionLogEvent;
import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.exchange.ExchangeTradingPort;
import org.dromara.quant.execution.ExecutionRecordService;
import org.dromara.quant.execution.ExecutionResult;
import org.dromara.quant.execution.TradeExecutionApplicationService;
import org.dromara.quant.execution.domain.QuantExecutionRecord;
import org.dromara.quant.market.MarketStreamHub;
import org.dromara.quant.market.UserDataStreamHub;
import org.dromara.quant.market.domain.QuantSymbol;
import org.dromara.quant.market.mapper.QuantSymbolMapper;
import org.dromara.quant.notification.QuantTradeNotificationService;
import org.dromara.quant.paper.PaperTradingService;
import org.dromara.quant.position.domain.QuantPosition;
import org.dromara.quant.position.mapper.QuantPositionMapper;
import org.dromara.quant.protection.ProtectionOrderService;
import org.dromara.quant.reconcile.TradingReconciliationService;
import org.dromara.quant.risk.LiveTradingGate;
import org.dromara.quant.risk.OpenCapacityService;
import org.dromara.quant.runtime.coordination.StrategyEntryGuardService;
import org.dromara.quant.runtime.coordination.StrategyRuntimeLeaseService;
import org.dromara.quant.runtime.coordination.mapper.StrategyRuntimeLedgerMapper;
import org.dromara.quant.runtime.domain.QuantStrategyPositionState;
import org.dromara.quant.runtime.service.StrategyPositionStateService;
import org.dromara.quant.strategy.*;
import org.dromara.quant.strategy.domain.QuantStrategy;
import org.dromara.quant.strategy.domain.QuantStrategyInstance;
import org.dromara.quant.strategy.domain.QuantStrategySymbol;
import org.dromara.quant.strategy.mapper.QuantStrategyInstanceMapper;
import org.dromara.quant.strategy.mapper.QuantStrategyMapper;
import org.dromara.quant.strategy.mapper.QuantStrategySymbolMapper;
import org.dromara.quant.util.QuantJsonMapper;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 事件驱动策略运行时。
 * <p>运行时通过 MarketStreamHub 接收实时行情；定时任务只负责发现实例、续租和 REST 对账，不再轮询每个实例的实时价格。
 */
@Slf4j
@Component
public class StrategyRuntimeService {
    /**
     * 量化模块配置。
     */
    private final QuantProperties properties;
    /**
     * 策略实例 Mapper。
     */
    private final QuantStrategyInstanceMapper instanceMapper;
    /**
     * 策略定义 Mapper。
     */
    private final QuantStrategyMapper strategyMapper;
    /**
     * 策略交易对 Mapper。
     */
    private final QuantStrategySymbolMapper strategySymbolMapper;
    /**
     * 交易凭证 Mapper。
     */
    private final QuantCredentialMapper credentialMapper;
    /**
     * 交易对 Mapper。
     */
    private final QuantSymbolMapper symbolMapper;
    /**
     * 持仓 Mapper。
     */
    private final QuantPositionMapper positionMapper;
    /**
     * 策略插件注册表。
     */
    private final StrategyPluginRegistry pluginRegistry;
    /**
     * 交易所统一网关。
     */
    private final ExchangeGateway gateway;
    /**
     * 公共行情流中心。
     */
    private final MarketStreamHub marketStreamHub;
    private final UserDataStreamHub userDataStreamHub;
    private final org.dromara.quant.market.AccountBalanceCache balanceCache;
    private final org.dromara.quant.risk.OpenCapacityService openCapacityService;
    private final org.dromara.quant.execution.TradingPreparationService preparationService;
    private final TradingReconciliationService reconciliationService;
    private final ExecutorService recoveryExecutor = Executors.newVirtualThreadPerTaskExecutor();
    /**
     * 策略斜率结果 SSE 事件中心。
     */
    private final StrategySlopeTelemetryHub slopeTelemetryHub;
    /**
     * 策略运行租约服务。
     */
    private final StrategyRuntimeLeaseService leaseService;
    /**
     * 同 K 线开仓保护服务。
     */
    private final StrategyEntryGuardService entryGuardService;
    /**
     * 策略持仓状态服务。
     */
    private final StrategyPositionStateService stateService;
    /**
     * 交易执行应用服务。
     */
    private final TradeExecutionApplicationService executionService;
    /**
     * 保护单服务。
     */
    private final ProtectionOrderService protectionOrderService;
    /**
     * PAPER 模拟交易服务。
     */
    private final PaperTradingService paperTradingService;
    /**
     * 执行记录服务。
     */
    private final ExecutionRecordService executionRecordService;
    /**
     * 交易通知服务。
     */
    private final QuantTradeNotificationService notificationService;
    /**
     * 用于执行需要数据库原子语义的 SQL 操作。
     */
    private final StrategyRuntimeLedgerMapper runtimeLedgerMapper;
    /**
     * 当前 JVM 节点正在运行的策略实例上下文。
     */
    private final Map<Long, ActiveRuntime> activeRuntimes = new ConcurrentHashMap<>();
    /**
     * Limit repeated blocked LIVE startup diagnostics to one per strategy per minute.
     */
    private final Map<Long, Long> nextLiveStreamOwnershipLogAt = new ConcurrentHashMap<>();
    /**
     * Limit detailed stale-exposure diagnostics while the strategy is blocked.
     */
    private final Map<Long, Long> nextExposureDiagnosticLogAt = new ConcurrentHashMap<>();
    @Autowired
    private org.dromara.quant.decision.TradeDecisionLogService decisionLogs;

    /**
     * 创建 StrategyRuntimeService 实例并初始化其依赖。
     */
    public StrategyRuntimeService(QuantProperties properties, QuantStrategyInstanceMapper instanceMapper, QuantStrategyMapper strategyMapper,
                                  QuantStrategySymbolMapper strategySymbolMapper, QuantCredentialMapper credentialMapper, QuantSymbolMapper symbolMapper, QuantPositionMapper positionMapper,
      StrategyPluginRegistry pluginRegistry, ExchangeGateway gateway, MarketStreamHub marketStreamHub, UserDataStreamHub userDataStreamHub,
                                  StrategySlopeTelemetryHub slopeTelemetryHub, StrategyRuntimeLeaseService leaseService, StrategyEntryGuardService entryGuardService,
        StrategyPositionStateService stateService, TradeExecutionApplicationService executionService, ProtectionOrderService protectionOrderService,
         PaperTradingService paperTradingService, ExecutionRecordService executionRecordService, QuantTradeNotificationService notificationService,
          StrategyRuntimeLedgerMapper runtimeLedgerMapper, org.dromara.quant.market.AccountBalanceCache balanceCache,
                                  org.dromara.quant.risk.OpenCapacityService openCapacityService, org.dromara.quant.execution.TradingPreparationService preparationService,
            TradingReconciliationService reconciliationService) {
        this.properties = properties;
        this.instanceMapper = instanceMapper;
        this.strategyMapper = strategyMapper;
        this.strategySymbolMapper = strategySymbolMapper;
        this.credentialMapper = credentialMapper;
        this.symbolMapper = symbolMapper;
        this.positionMapper = positionMapper;
        this.pluginRegistry = pluginRegistry;
        this.gateway = gateway;
        this.marketStreamHub = marketStreamHub;
        this.userDataStreamHub = userDataStreamHub;
        this.slopeTelemetryHub = slopeTelemetryHub;
        this.leaseService = leaseService;
        this.entryGuardService = entryGuardService;
        this.stateService = stateService;
        this.executionService = executionService;
        this.protectionOrderService = protectionOrderService;
        this.paperTradingService = paperTradingService;
        this.executionRecordService = executionRecordService;
        this.notificationService = notificationService;
        this.runtimeLedgerMapper = runtimeLedgerMapper;
        this.balanceCache = balanceCache;
        this.openCapacityService = openCapacityService;
        this.preparationService = preparationService;
        this.reconciliationService = reconciliationService;
    }

    /**
     * 返回运行时生效的保证金模式；交易对覆盖优先于策略默认值。
     */
    private static String effectiveMarginMode(ActiveRuntime runtime) {
        String mode = runtime.strategySymbol.getMarginMode();
        if (mode == null || mode.isBlank()) {
            mode = runtime.strategy.getDefaultMarginMode();
        }
        return mode == null || mode.isBlank() ? "ISOLATED" : mode.toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * 计算基础价格收益率。
     */
    private static BigDecimal calculateReturnRate(String direction, BigDecimal closePrice, BigDecimal entryPrice) {
        if (closePrice == null || entryPrice == null || entryPrice.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal delta = "SHORT".equalsIgnoreCase(direction) ? entryPrice.subtract(closePrice) : closePrice.subtract(entryPrice);
        return delta.divide(entryPrice, 12, RoundingMode.HALF_UP);
    }

    /**
     * 计算包含杠杆后的策略回报率。
     */
    private static BigDecimal leveragedReturnRate(ActiveRuntime runtime, String direction, BigDecimal closePrice, BigDecimal entryPrice) {
        BigDecimal raw = calculateReturnRate(direction, closePrice, entryPrice);
        int leverage = runtime == null || runtime.strategySymbol == null || runtime.strategySymbol.getLeverage() == null ? (runtime == null || runtime.strategy == null ? 1 :
         Math.max(1, runtime.strategy.getDefaultLeverage())) : Math.max(1, runtime.strategySymbol.getLeverage());
        if (runtime != null && runtime.state != null && runtime.state.getFrozenLeverage() != null) {
            leverage = runtime.state.getFrozenLeverage();
        }
        return raw.multiply(BigDecimal.valueOf(leverage));
    }

    /**
     * 根据价格变化和数量计算盈亏金额。
     */
    private static BigDecimal calculateProfitAmount(String direction, BigDecimal closePrice, BigDecimal entryPrice, BigDecimal quantity) {
        if (closePrice == null || entryPrice == null || quantity == null) {
            return null;
        }
        BigDecimal delta = "SHORT".equalsIgnoreCase(direction) ? entryPrice.subtract(closePrice) : closePrice.subtract(entryPrice);
        return delta.multiply(quantity);
    }

    /**
     * 清理已确认完成的待执行动作。
     */
    private static void clearPending(QuantStrategyPositionState state) {
        state.setPendingAction(null);
        state.setPendingClientOrderId(null);
        state.setPendingOrderId(null);
        state.setPendingOrderTime(null);
        state.setPendingIdempotencyKey(null);
        state.setPendingNotFoundConfirmations(0);
    }

    /**
     * 生成带业务语义的交易执行幂等键。
     */
    private static String buildIdempotencyKey(ActiveRuntime runtime, String action, Instant time) {
        return "STRATEGY:" + runtime.instance.getId() + ":" + action + ":" + time.toEpochMilli();
    }

    /**
     * 读取正整数配置，非法时使用默认值。
     */
    private static int positiveInt(Object value, int fallback) {
        if (value instanceof Number number && number.intValue() > 0) {
            return number.intValue();
        }
        if (value != null) {
            try {
                int parsed = Integer.parseInt(String.valueOf(value));
                if (parsed > 0) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    /**
     * 在策略启动时校验保护参数范围，阻止非法比例进入下单链路。
     */
    private static void validateProtectionRatios(QuantStrategy strategy, QuantStrategySymbol symbol) {
        for (BigDecimal ratio : Arrays.asList(strategy.getDefaultStopLossRatio(), symbol.getStopLossRatio())) {
            if (ratio != null && (ratio.signum() <= 0 || ratio.compareTo(BigDecimal.ONE) >= 0)) {
                throw new IllegalArgumentException("默认/绑定止损比例必须 > 0 且 < 1");
            }
        }
    }

    private static void freezeOrderEntryPrice(QuantStrategyPositionState state, ExchangeGateway.OrderSnapshot order) {
        if (order != null && order.averagePrice() != null && order.averagePrice().signum() > 0) {
            state.setEntryPrice(order.averagePrice());
            state.setHighestPriceSinceEntry(order.averagePrice());
            state.setLowestPriceSinceEntry(order.averagePrice());
        }
    }

    /**
     * 合并交易所侧完全相同的条件单目标。策略退出判断仍保留全部信号，
     * 这里只避免硬止损、策略止损和交易对止损重复创建相同的 Binance 单。
     */
    static List<ProtectionSignal> deduplicateExchangeProtectionSignals(List<ProtectionSignal> signals, QuantPosition position, BigDecimal initialQuantity,
     BigDecimal hardStopRatio, ExchangeGateway.SymbolRule rule, int leverage) {
        if (signals == null || signals.isEmpty() || position == null || position.getQuantity() == null) {
            return List.of();
        }
        Set<String> targets = new HashSet<>();
        if (hardStopRatio != null && hardStopRatio.signum() > 0 && hardStopRatio.compareTo(BigDecimal.ONE) < 0 && position.getEntryPrice() != null && position.getEntryPrice().signum() > 0) {
            BigDecimal stop = KlineSlopeRateStrategy.priceForReturn(position.getEntryPrice(), position.getPositionSide(), hardStopRatio.negate(), leverage);
            addProtectionTarget(targets, "STOP_MARKET", position.getQuantity(), stop, rule);
        }
        List<ProtectionSignal> result = new ArrayList<>();
        for (ProtectionSignal signal : signals) {
            if (signal == null) {
                continue;
            }
            if (signal.triggerPrice() == null) {
                result.add(signal);
                continue;
            }
            if (!Set.of("LOCAL_STOP", "SYMBOL_STOP").contains(signal.type())) {
                result.add(signal);
                continue;
            }
            BigDecimal base = signal.quantityBase() == ProtectionSignal.QuantityBase.INITIAL && initialQuantity != null ? initialQuantity : position.getQuantity();
            BigDecimal quantity = base.multiply(signal.quantityRate() == null ? BigDecimal.ONE : signal.quantityRate()).min(position.getQuantity());
            if (addProtectionTarget(targets, signal.orderType(), quantity, signal.triggerPrice(), rule)) {
                result.add(signal);
            }
        }
        return List.copyOf(result);
    }

    private static boolean addProtectionTarget(Set<String> targets, String orderType, BigDecimal quantity, BigDecimal triggerPrice, ExchangeGateway.SymbolRule rule) {
        if (quantity == null || quantity.signum() <= 0 || triggerPrice == null || triggerPrice.signum() <= 0) {
            return true;
        }
        if (rule != null) {
            quantity = org.dromara.quant.exchange.QuantityNormalizer.quantity(quantity, rule, "MARKET");
            triggerPrice = org.dromara.quant.exchange.QuantityNormalizer.price(triggerPrice, rule);
        }
        String key =
         (orderType == null ? "STOP_MARKET" : orderType.toUpperCase(Locale.ROOT)) + ":" + quantity.stripTrailingZeros().toPlainString() + ":" + triggerPrice.stripTrailingZeros().toPlainString();
        return targets.add(key);
    }

    /**
     * 确保指定策略实例拥有有效运行上下文和租约。
     */

    /**
     * 按配置周期发现/停止实例，并同步一次真实持仓；实时价格不在这里轮询。
     */
    public void supervise() {
        log.debug("策略运行时监督任务开始 runtimeEnabled={}", properties.getRuntime().isEnabled());
        if (!properties.getRuntime().isEnabled()) {
            stopAll();
            return;
        }
        List<QuantStrategyInstance> instances = instanceMapper.selectList(new LambdaQueryWrapper<QuantStrategyInstance>().eq(QuantStrategyInstance::getStatus, "RUNNING"));
        // 停止开仓不等于停止已有持仓的保护，重启后仍恢复未平仓实例。
        Set<Long> discovered = new HashSet<>();
        instances.forEach(instance -> discovered.add(instance.getId()));
        for (QuantStrategyPositionState state : stateService.findUnclosed()) {
            if (discovered.add(state.getInstanceId())) {
                QuantStrategyInstance instance = instanceMapper.selectById(state.getInstanceId());
                if (instance != null && "STOPPED".equals(instance.getStatus())) {
                    instances.add(instance);
                }
            }
        }
        log.debug("策略运行时监督任务发现运行/持仓保护实例数量={}", instances.size());
        Set<Long> activeIds = new HashSet<>();
        for (QuantStrategyInstance instance : instances) {
            try {
                if (ensureRuntime(instance)) {
                    activeIds.add(instance.getId());
                }
            } catch (RuntimeException ex) {
                recordError(instance, ex);
            }
        }
        activeRuntimes.keySet().removeIf(id -> {
            if (!activeIds.contains(id)) {
                ActiveRuntime runtime = activeRuntimes.remove(id);
                closeRuntime(runtime);
                return true;
            }
            return false;
        });
    }

    /**
     * 每 15 秒做一次持仓/订单事实同步，用于 WebSocket 丢消息后的恢复。
     */
    public void reconcileRunningPositions() {
        activeRuntimes.values().forEach(this::requestReconciliation);
    }

    /**
     * 将账户事实刷新请求合并排队，避免每条行情事件都发起 REST 查询。
     */
    private void requestReconciliation(ActiveRuntime runtime) {
        synchronized (runtime) {
            if (runtime.closed || System.currentTimeMillis() < runtime.nextReconcileAt) {
                return;
            }
            runtime.reconcileRequested.set(true);
            if (runtime.exitCoordinating.get() || !runtime.reconciling.compareAndSet(false, true)) {
                return;
            }
        }
        recoveryExecutor.execute(() -> {
            try {
                do {
                    runtime.reconcileRequested.set(false);
                    reconcilePosition(runtime);
                } while (!runtime.closed && runtime.reconcileRequested.get() && System.currentTimeMillis() >= runtime.nextReconcileAt);
            } finally {
                runtime.reconciling.set(false);
                if (!runtime.closed && runtime.reconcileRequested.get()) {
                    requestReconciliation(runtime);
                }
            }
        });
    }

    /**
     * 仅在持仓事实完成核验后同步策略状态；失败时保留暂停决策状态。
     */
    private void reconcilePosition(ActiveRuntime runtime) {
        synchronized (runtime) {
            if (runtime.closed || runtime.exitCoordinating.get()) {
                return;
            }
            runtime.positionReconciling = true;
        }
        try {
            if (reconcilePendingExecution(runtime)) {
                synchronized (runtime) {
                    if (!runtime.closed && runtime.position != null) {
                        ensureHardStop(runtime);
                    }
                }
                return;
            }
            retryProtectionCleanupIfNeeded(runtime);
            QuantStrategyPositionState expectedState;
            boolean expectedPending;
            List<Object> expectedPendingIdentity;
            QuantPosition expectedPosition;
            BigDecimal expectedQuantity;
            synchronized (runtime) {
                if (runtime.closed) {
                    return;
                }
                if (runtime.hasPendingAction()) {
                    runtime.reconcileRequested.set(true);
                    return;
                }
                expectedState = runtime.state;
                expectedPending = runtime.hasPendingAction();
                expectedPendingIdentity = expectedState == null ? List.of() : pendingIdentity(expectedState);
                expectedPosition = runtime.position;
                expectedQuantity = expectedPosition == null ? null : expectedPosition.getQuantity();
            }
            if (!leaseService.acquireOrRenew(runtime.instance.getId(), runtime.credential.getId(), runtime.symbol.getSymbol())) {
                return;
            }
            // 在REST前捕获数据库基线；慢查询或并发平仓后只能条件更新，不能覆盖新账本。
            List<QuantPosition> baseline =
        positionMapper.selectList(new LambdaQueryWrapper<QuantPosition>().eq(QuantPosition::getCredentialId, runtime.credential.getId()).eq(QuantPosition::getSymbol,
            runtime.symbol.getSymbol())).stream().map(position -> {
                QuantPosition copy = new QuantPosition();
                BeanUtils.copyProperties(position, copy);
                return copy;
            }).toList();
            List<ExchangeGateway.PositionSnapshot> snapshots = "PAPER".equalsIgnoreCase(runtime.credential.getRunMode()) ? null :
             reconciliationService.refreshSymbolPosition(runtime.credential, runtime.symbol.getSymbol(), executionService.toCredential(runtime.credential));
            synchronized (runtime) {
                if (runtime.closed) {
                    return;
                }
                if (runtime.state != expectedState || runtime.hasPendingAction() != expectedPending || !Objects.equals(expectedPendingIdentity, runtime.state == null ?
List.of() : pendingIdentity(runtime.state)) || runtime.position != expectedPosition || runtime.position != null && !Objects.equals(runtime.position.getQuantity(), expectedQuantity)) {
                    runtime.reconcileRequested.set(true);
                    return;
                }
            }
            QuantPosition refreshed = loadReconciledPosition(runtime, baseline, snapshots);
            BigDecimal firstExecuted = null;
            if (refreshed != null && expectedState != null && expectedState.getPositionCycleKey() != null && !Boolean.TRUE.equals(expectedState.getFirstTakeProfitTriggered()) && !"PAPER".equalsIgnoreCase(runtime.credential.getRunMode())) {
                refreshed.setProtectionCycleKey(expectedState.getPositionCycleKey());
                firstExecuted = protectionOrderService.firstTakeProfitExecutedQuantity(runtime.credential.getId(), refreshed);
            }
            if (firstExecuted != null && expectedState.getInitialPositionQty() != null && refreshed.getQuantity().compareTo(expectedState.getInitialPositionQty().subtract(firstExecuted).subtract(expectedState.getFirstTakeProfitLocalExecutedQty() == null ? BigDecimal.ZERO : expectedState.getFirstTakeProfitLocalExecutedQty()).max(BigDecimal.ZERO)) > 0) {
                throw new ProtectionOrderService.ProtectionExecutionPendingException();
            }
            boolean firstTpFilled =
             firstExecuted != null && expectedState.getFirstTakeProfitTargetQty() != null && firstExecuted.add(expectedState.getFirstTakeProfitLocalExecutedQty() == null ?
            BigDecimal.ZERO : expectedState.getFirstTakeProfitLocalExecutedQty()).compareTo(expectedState.getFirstTakeProfitTargetQty()) >= 0;
            boolean leaseHeld = leaseService.acquireOrRenew(runtime.instance.getId(), runtime.credential.getId(), runtime.symbol.getSymbol());
            synchronized (runtime) {
                if (runtime.state != expectedState || runtime.hasPendingAction() != expectedPending || !Objects.equals(expectedPendingIdentity, runtime.state == null ?
List.of() : pendingIdentity(runtime.state)) || runtime.position != expectedPosition || runtime.position != null && !Objects.equals(runtime.position.getQuantity(), expectedQuantity)) {
                    runtime.reconcileRequested.set(true);
                    return;
                }
                if (runtime.closed) {
                    return;
                }
                if (!leaseHeld) {
                    return;
                }
                QuantPosition previousPosition = runtime.position;
                runtime.position = refreshed;
                if (runtime.position != null) {
                    if (previousPosition != null && previousPosition.getQuantity() != null && runtime.position.getQuantity() != null && runtime.position.getQuantity().compareTo(previousPosition.getQuantity()) < 0) {
                        BigDecimal closedQty = previousPosition.getQuantity().subtract(runtime.position.getQuantity());
                        recordPositionDecrease(runtime, previousPosition, closedQty, firstTpFilled ? "交易所首次止盈保护已确认减仓" : "交易所保护单或外部操作导致部分减仓，具体触发原因待确认");
                        BigDecimal closePrice = runtime.currentPrice() != null ? runtime.currentPrice() : runtime.position.getMarkPrice();
                        BigDecimal profitRate = leveragedReturnRate(runtime, previousPosition.getPositionSide(), closePrice, previousPosition.getEntryPrice());
                        BigDecimal profitAmount = calculateProfitAmount(previousPosition.getPositionSide(), closePrice, previousPosition.getEntryPrice(), closedQty);
                        notificationService.notifyClose(runtime.strategy, runtime.credential, runtime.symbol.getSymbol(), previousPosition.getPositionSide(), closedQty,
previousPosition.getEntryPrice(), closePrice, profitAmount, profitRate, "EXCHANGE_PARTIAL_CLOSE", "交易所保护单/外部操作导致部分减仓，经 REST 对账确认");
                    }
                    if (firstExecuted != null) {
                        PositionProtectionState.confirmExchangeFirstProfit(runtime.state, firstExecuted);
                    }
                    syncRuntimeStateWithPosition(runtime, firstTpFilled);
                } else if (runtime.state != null && !runtime.hasPendingAction() && (!"CLOSED".equals(runtime.state.getStatus())
                    || Boolean.TRUE.equals(runtime.state.getRecoveryRequired()) || Boolean.TRUE.equals(runtime.state.getUnsafeRecovery()))) {
                    // FIXED: 交易所已确认空仓。无论保护单清理成功与否，state 都必须归零，
                    // 否则残留的 status=OPEN/EXIT_PENDING + position_qty>0 会永远占用策略最大持仓数名额，
                    // 尤其在 LIVE 模式（真实挂过 STOP/TP 条件单，撤销可能失败）反复出现。
                    // 清理失败时改用 protectionCleanupPending 独立阻断开新仓，不再锁住 state。
                    boolean protectionCleaned = cancelStaleProtection(runtime);
                    if (previousPosition != null) {
                        recordPositionDecrease(runtime, previousPosition, previousPosition.getQuantity(), "交易所保护单或外部操作导致持仓关闭，具体触发原因待确认");
                        BigDecimal closePrice = runtime.currentPrice() != null ? runtime.currentPrice() : previousPosition.getMarkPrice();
                        BigDecimal profitRate = leveragedReturnRate(runtime, previousPosition.getPositionSide(), closePrice, previousPosition.getEntryPrice());
                        BigDecimal profitAmount = calculateProfitAmount(previousPosition.getPositionSide(), closePrice, previousPosition.getEntryPrice(),
                         previousPosition.getQuantity());
                        notificationService.notifyClose(runtime.strategy, runtime.credential, runtime.symbol.getSymbol(), previousPosition.getPositionSide(),
            previousPosition.getQuantity(), previousPosition.getEntryPrice(), closePrice, profitAmount, profitRate, "EXCHANGE_PROTECTION_OR_EXTERNAL",
                         "交易所保护单/外部操作导致持仓关闭，经 REST 对账确认");
                    }
                    // 无条件将 state 归零：status=CLOSED + position_qty=0，从"最大持仓数"统计中排除。
                    stateService.markClosed(runtime.state);
                    runtime.state = stateService.findByInstance(runtime.instance.getId());
                    if (!protectionCleaned) {
                        // 保护单未清理干净：保留阻断开仓标志，避免残留 STOP/TP 在后续触发时造成意外减仓。
                        runtime.protectionCleanupPending = true;
                        log.warn("交易所已空仓但保护单未完全清理，已阻断开新仓 instanceId={} positionId={}", runtime.instance.getId(), previousPosition == null ? null : previousPosition.getId());
                    }
                }
                if (runtime.position != null && runtime.state == null) {
                    runtime.state = recoverState(runtime);
                }
                if (runtime.position != null && runtime.state != null) {
                    ensureHardStop(runtime);
                    BigDecimal current = runtime.currentPrice() != null ? runtime.currentPrice() : runtime.position.getMarkPrice();
                    if (current != null && current.signum() > 0) {
                        syncPluginProtections(runtime, buildContext(runtime, current, new ExchangeGateway.MarketEvent("RECONCILE", runtime.symbol.getSymbol(),
 runtime.strategySymbol.getIntervalCode(), current, null, Instant.now())));
                    }
                }
            }
            runtime.nextReconcileAt = 0L;
            if (runtime.instance.getLastError() != null && runtime.instance.getLastError().startsWith("Binance REST 请求排队")) {
                runtime.instance.setLastError("");
                instanceMapper.updateById(runtime.instance);
            }
        } catch (TradingReconciliationService.SnapshotSupersededException ex) {
            runtime.nextReconcileAt = System.currentTimeMillis() + 5_000L;
            log.info("较早发起的交易对持仓快照已被新快照取代，等待后续对账 instanceId={} symbol={}", runtime.instance.getId(), runtime.symbol.getSymbol());
        } catch (RuntimeException ex) {
            runtime.nextReconcileAt = System.currentTimeMillis() + 5_000L;
            recordError(runtime.instance, ex);
        } finally {
            synchronized (runtime) {
                runtime.positionReconciling = false;
            }
        }
    }

    /**
     * 确保指定策略实例拥有有效运行上下文和租约。
     * FIXED: 策略/凭证/交易对被停用但存在未平仓持仓时，进入保护模式而不是完全停止。
     */
    private boolean ensureRuntime(QuantStrategyInstance instance) {
        QuantStrategy strategy = strategyMapper.selectById(instance.getStrategyId());
        QuantStrategySymbol strategySymbol = strategySymbolMapper.selectById(instance.getStrategySymbolId());
        QuantCredential credential = credentialMapper.selectById(instance.getCredentialId());
        if (strategy == null || strategySymbol == null || credential == null) {
            stopInactiveRuntime(instance.getId());
            return false;
        }
        QuantSymbol symbol = symbolMapper.selectById(strategySymbol.getSymbolId());
        if (symbol == null) {
            stopInactiveRuntime(instance.getId());
            return false;
        }

        QuantStrategyPositionState existingState = stateService.findByInstance(instance.getId());
        boolean hasOpenPosition =
                            existingState != null && !"CLOSED".equals(existingState.getStatus()) && ((existingState.getPositionQty() != null && existingState.getPositionQty().signum() > 0) || existingState.getPendingAction() != null || "PROTECTION_CLEANUP".equals(existingState.getStatus()));

        boolean tradingAllowed =
         "1".equals(strategy.getStatus()) && "0".equals(strategySymbol.getStatus()) && "0".equals(credential.getStatus()) && "0".equals(symbol.getStatus()) && (!"PAPER".equalsIgnoreCase(credential.getRunMode()) || properties.getPaper().isEnabled());

        if (!hasOpenPosition && !tradingAllowed) {
            stopInactiveRuntime(instance.getId());
            return false;
        }
        boolean protectionOnly = !tradingAllowed && hasOpenPosition;

        // The account-level private-stream lease is established independently by the LIVE
        // supervisor. A strategy on a non-owner node must not start or hold a position lease.
        // During startup the owner may not exist yet; retry at the next supervision tick.
        if ("LIVE".equalsIgnoreCase(credential.getRunMode()) && !userDataStreamHub.hasLiveStreamOwnership(credential.getId())) {
            long now = System.currentTimeMillis();
            Long nextLogAt = nextLiveStreamOwnershipLogAt.get(instance.getId());
            if (nextLogAt == null || now >= nextLogAt) {
                nextLiveStreamOwnershipLogAt.put(instance.getId(), now + 60_000L);
                log.warn("LIVE策略尚未建立本节点私有数据流租约，暂不启动行情订阅 instanceId={} credentialId={} symbol={}：请检查LivePositionStreamTriggers租约与私有WS连接", instance.getId(), credential.getId(),
                 symbol.getSymbol());
            }
            return false;
        }
        nextLiveStreamOwnershipLogAt.remove(instance.getId());

        if (!leaseService.acquireOrRenew(instance.getId(), credential.getId(), symbol.getSymbol())) {
            // 无法确认物理仓位独占权时保守停止本地运行时，避免两个实例同时操作同一交易所仓位。
            log.warn("策略运行时获取租约失败 instanceId={} credentialId={} symbol={}", instance.getId(), credential.getId(), symbol.getSymbol());
            stopInactiveRuntime(instance.getId());
            return false;
        }

        ActiveRuntime current = activeRuntimes.get(instance.getId());
        if (current != null && current.matches(strategy.getPluginCode(), symbol.getSymbol(), strategySymbol.getIntervalCode(), credential.getId())) {
            synchronized (current) {
                Map<String, Object> refreshed = mergedParams(strategy, strategySymbol);
                current.plugin.validateConfig(refreshed);
                validateProtectionRatios(strategy, strategySymbol);
                current.instance = instance;
                current.strategy = strategy;
                current.strategySymbol = strategySymbol;
                current.credential = credential;
                current.params = refreshed;
                current.protectionOnly = protectionOnly;
                current.nextLeaseRenewAt = System.currentTimeMillis() + leaseRenewIntervalMillis();
            }
            prepareTrading(current);
            return true;
        }
        if (current != null) {
            closeRuntime(current);
            activeRuntimes.remove(instance.getId(), current);
            if (!leaseService.acquireOrRenew(instance.getId(), credential.getId(), symbol.getSymbol())) {
                return false;
            }
        }

        StrategyPlugin plugin = pluginRegistry.require(strategy.getPluginCode());
        Map<String, Object> params = mergedParams(strategy, strategySymbol);
        plugin.validateConfig(params);
        validateProtectionRatios(strategy, strategySymbol);
        ActiveRuntime runtime = new ActiveRuntime(instance, strategy, strategySymbol, credential, symbol, plugin, params);
        runtime.protectionOnly = protectionOnly;
        runtime.protectionRule = "PAPER".equalsIgnoreCase(credential.getRunMode()) ? null : gateway.queryTradingRule(executionService.toCredential(credential), symbol.getSymbol());
        if (!"PAPER".equalsIgnoreCase(credential.getRunMode()) && runtime.protectionRule == null) {
            throw new IllegalStateException("交易规则未就绪，拒绝启动策略");
        }
        runtime.state = stateService.findByInstance(instance.getId());
        runtime.position = loadPosition(credential, symbol.getSymbol(), executionService.toCredential(credential));
        if (runtime.position != null && runtime.state == null) {
            runtime.state = recoverState(runtime);
        }
        if (runtime.position != null && !hasFrozenProtectionFacts(runtime)) {
            runtime.state.setRecoveryRequired(true);
            runtime.state.setUnsafeRecovery(true);
            stateService.save(runtime.state);
            runtime.instance.setLastError("既有持仓冻结参数/首次止盈事实不完整，保留现有委托，需人工核查");
            instanceMapper.updateById(runtime.instance);
            log.error("既有持仓保护事实不完整，保留现有委托，需人工核查 instanceId={}", instance.getId());
        }
        try {
            synchronized (runtime) {
                activeRuntimes.put(instance.getId(), runtime);
                if (runtime.position != null) {
                    ensureHardStop(runtime);
                }
                runtime.subscription = marketStreamHub.subscribe(credential.getRunMode(), symbol.getSymbol(), strategySymbol.getIntervalCode(), event -> onMarketEvent(runtime,
            event), ready -> runtime.marketStreamReady = ready);
                if (!"PAPER".equalsIgnoreCase(credential.getRunMode())) {
                    runtime.userSubscription = userDataStreamHub.subscribe(credential, event -> {
                        if (event != null) {
                            requestReconciliation(runtime);
                        }
                    });
                }
            }
            prepareTrading(runtime);
        } catch (RuntimeException ex) {
            // A failed private-stream subscription or trading preparation must
            // not leave a half-initialized runtime with a live market socket.
            activeRuntimes.remove(instance.getId(), runtime);
            try {
                closeRuntime(runtime);
            } catch (RuntimeException cleanupError) {
                ex.addSuppressed(cleanupError);
            }
            throw ex;
        }
        runtime.nextLeaseRenewAt = System.currentTimeMillis() + leaseRenewIntervalMillis();
        log.info("策略运行时已启动 instanceId={} strategy={} plugin={} symbol={} interval={} mode={} protectionOnly={}", instance.getId(), strategy.getStrategyName(),
                strategy.getPluginCode(), symbol.getSymbol(), strategySymbol.getIntervalCode(), credential.getRunMode(), protectionOnly);
        return true;
    }

    /**
     * 在首次决策前确认账户模式、杠杆和交易对规则均已准备完成。
     */
    private void prepareTrading(ActiveRuntime runtime) {
        // FIXED: 保护模式不准备新交易设置。
        if (runtime.protectionOnly) {
            return;
        }
        if (!"PAPER".equalsIgnoreCase(runtime.credential.getRunMode())) {
            int leverage = runtime.strategySymbol.getLeverage() == null ? runtime.strategy.getDefaultLeverage() : runtime.strategySymbol.getLeverage();
            executionService.prepareOpenForRuntime(runtime.credential.getId(), runtime.symbol.getSymbol(), leverage, effectiveMarginMode(runtime));
        }
    }

    /**
     * 停止禁用、失效或失去租约的策略 Runtime。
     */
    private void stopInactiveRuntime(Long instanceId) {
        ActiveRuntime runtime = activeRuntimes.remove(instanceId);
        if (runtime != null) {
            closeRuntime(runtime);
        }
    }

    /**
     * 根据租约有效期计算安全续租周期。
     */
    private long leaseRenewIntervalMillis() {
        long leaseSeconds = Math.max(3L, properties.getRuntime().getLeaseSeconds());
        return Math.max(1_000L, leaseSeconds * 1_000L / 3L);
    }

    /**
     * 处理实时事件前确认当前节点仍持有有效租约。
     */
    private boolean ensureEventLease(ActiveRuntime runtime) {
        long now = System.currentTimeMillis();
        if (now < runtime.nextLeaseRenewAt) {
            return true;
        }
        boolean acquired = leaseService.acquireOrRenew(runtime.instance.getId(), runtime.credential.getId(), runtime.symbol.getSymbol());
        runtime.nextLeaseRenewAt = acquired ? now + leaseRenewIntervalMillis() : now + 1_000L;
        return acquired;
    }

    /**
     * 处理实时行情事件并驱动策略计算。
     */
    private void onMarketEvent(ActiveRuntime runtime, ExchangeGateway.MarketEvent event) {
        synchronized (runtime) {
            if (runtime.closed || event == null) {
                return;
            }
            if (!ensureEventLease(runtime)) {
                runtime.marketHealthy = false;
                return;
            }
            if ("CONNECTING".equals(event.type())) {
                // 包括K线换周期后的REST校验；WS仍可能保持连接，不能报告为断线。
                // 必须保留交易门控，直到MarketStreamHub重新确认行情就绪。
                runtime.marketHealthy = false;
                runtime.latestTradePrice = null;
                runtime.latestTradeEventAt = null;
                return;
            }
            if ("DISCONNECTED".equals(event.type()) || "ERROR".equals(event.type())) {
                log.warn("策略行情流断开 instanceId={} symbol={} status={}", runtime.instance.getId(), runtime.symbol.getSymbol(), event.type());
                runtime.marketHealthy = false;
                runtime.latestTradePrice = null;
                runtime.latestTradeEventAt = null;
                return;
            }
            if ("CONNECTED".equals(event.type())) {
                runtime.marketHealthy = true;
                return;
            }
            if (event.kline() != null) {
                runtime.updateKline(event.kline());
                runtime.market.lastKlineEventAt = event.eventTime();
                runtime.lastEvaluatedKlineOpenTime = event.kline().openTime();
            }
            if (event.replay()) {
                // 成交补偿仅恢复开仓后的峰值，不产生回溯下单。
                if (!runtime.exitCoordinating.get() && event.price() != null && runtime.state != null && runtime.state.getOpenedAt() != null && event.eventTime() != null && !event.eventTime().isBefore(runtime.state.getOpenedAt().atZone(java.time.ZoneId.systemDefault()).toInstant())) {
                    updateRuntimeExtrema(runtime, event.price());
                }
                return;
            }
            if (event.price() != null && "TRADE".equals(event.type())) {
                runtime.latestTradePrice = event.price();
                runtime.latestTradeEventAt = event.eventTime();
                if (runtime.position != null && !runtime.exitCoordinating.get()) {
                    updateRuntimeExtrema(runtime, event.price());
                }
                if (runtime.exitCoordinating.get()) {
                    runtime.exitWindowHigh = runtime.exitWindowHigh == null ? event.price() : runtime.exitWindowHigh.max(event.price());
                    runtime.exitWindowLow = runtime.exitWindowLow == null ? event.price() : runtime.exitWindowLow.min(event.price());
                }
            }
            if (event.price() != null || event.kline() != null) {
                if (runtime.position != null && !runtime.exitCoordinating.get() && "PAPER".equalsIgnoreCase(runtime.credential.getRunMode()) && event.price() != null) {
                    paperTradingService.updateMarkPrice(runtime.position, event.price());
                }
                evaluate(runtime, event);
            }
        }
    }

    /**
     * 基于最新行情、持仓和策略状态执行一次决策。
     */
    private void evaluate(ActiveRuntime runtime, ExchangeGateway.MarketEvent event) {
        long nowMillis = System.currentTimeMillis();
        if (!event.replay()) {
            if (runtime.marketHealthy && runtime.marketStreamReady) {
                runtime.marketUnreadySinceAt = 0;
            } else if (runtime.marketUnreadySinceAt == 0) {
                runtime.marketUnreadySinceAt = nowMillis;
            }
        }
        if (!runtime.marketHealthy || !runtime.marketStreamReady || event.replay()) {
            // 行情未就绪时，新鲜的实时 TRADE/KLINE 仍可更新斜率遥测；回放或未就绪行情不能触发开仓、平仓判断。
            if (!event.replay() && runtime.plugin instanceof KlineSlopeRateStrategy && ("TRADE".equalsIgnoreCase(event.type()) || "KLINE".equalsIgnoreCase(event.type())) && event.eventTime() != null && event.receivedAt() != null && !event.eventTime().isAfter(Instant.now().plusSeconds(1)) && !event.eventTime().isBefore(Instant.now().minusMillis(Math.max(1, properties.getMarket().getMaxEventAgeMillis()))) && !event.receivedAt().isBefore(Instant.now().minusMillis(Math.max(1, properties.getMarket().getMaxEventAgeMillis())))) {
                BigDecimal displayPrice = event.price() != null ? event.price() : runtime.currentPrice();
                if (displayPrice != null && displayPrice.signum() > 0) {
                    publishSlopeTelemetry(runtime, buildContext(runtime, displayPrice, event));
                }
            }
            if (!event.replay()) {
                logUnavailableEntry(runtime, event, "MARKET_UNREADY", "行情尚未就绪，暂停策略决策");
            }
            // 所有交易环境共用该门控；斜率遥测可更新不代表行情已满足交易决策条件。
            long unreadyGraceMillis = Math.max(1, properties.getMarket().getIdleTimeoutSeconds()) * 1_000L;
            if (!event.replay() && ("TRADE".equals(event.type()) || "KLINE".equals(event.type())) && runtime.marketUnreadySinceAt > 0 &&
                nowMillis - runtime.marketUnreadySinceAt >= unreadyGraceMillis && nowMillis >= runtime.nextMarketUnreadyLogAt) {
                runtime.nextMarketUnreadyLogAt = nowMillis + 60_000L;
                log.warn("策略行情门控持续未就绪 instanceId={} mode={} symbol={} eventType={} marketHealthy={} marketStreamReady={} unreadyDurationMillis={} eventTime={}",
                    runtime.instance.getId(), runtime.credential.getRunMode(), runtime.symbol.getSymbol(), event.type(), runtime.marketHealthy, runtime.marketStreamReady,
                    nowMillis - runtime.marketUnreadySinceAt, event.eventTime());
            }
            // 限频输出行情未就绪的调试信息，避免高频行情事件刷屏。
            if (log.isDebugEnabled() && System.currentTimeMillis() >= runtime.nextLatencyLogAt) {
                runtime.nextLatencyLogAt = System.currentTimeMillis() + 5_000L;
                log.debug("evaluate 早退[MARKET_UNREADY] instanceId={} symbol={} marketHealthy={} marketStreamReady={} replay={} eventType={}", runtime.instance.getId(),
                 runtime.symbol.getSymbol(), runtime.marketHealthy, runtime.marketStreamReady, event.replay(), event.type());
            }
            return;
        }
        Instant decisionAt = Instant.now();
        if (log.isDebugEnabled() && System.currentTimeMillis() >= runtime.nextLatencyLogAt) {
            runtime.nextLatencyLogAt = System.currentTimeMillis() + 5_000L;
            log.debug("策略行情分段延迟 instanceId={} symbol={} receiveDelayMillis={} queueWaitMillis={} eventAgeMillis={}", runtime.instance.getId(), runtime.symbol.getSymbol(),
             event.eventTime() == null ? null : java.time.Duration.between(event.eventTime(), event.receivedAt()).toMillis(), java.time.Duration.between(event.receivedAt(),
decisionAt).toMillis(), event.eventTime() == null ? null : java.time.Duration.between(event.eventTime(), decisionAt).toMillis());
        }
        Instant freshnessDeadline = decisionAt.minusMillis(Math.max(1, properties.getMarket().getMaxEventAgeMillis()));
        if (event.receivedAt().isBefore(freshnessDeadline) || event.eventTime() == null || event.eventTime().isBefore(freshnessDeadline) || event.eventTime().isAfter(Instant.now().plusSeconds(1))) {
            logUnavailableEntry(runtime, event, "STALE_MARKET", "行情事件过期或时间异常，暂停本次决策");
            log.warn("策略行情事件过期或时间异常，暂停本次决策并请求持仓核实 instanceId={} symbol={} eventTime={} receivedAt={}", runtime.instance.getId(), runtime.symbol.getSymbol(),
                event.eventTime(), event.receivedAt());
            requestReconciliation(runtime);
            return;
        }
        // 策略开平仓判断使用成交价或 K 线价；MARK 价格不直接触发策略开平仓。
        if (!"TRADE".equalsIgnoreCase(event.type()) && !"KLINE".equalsIgnoreCase(event.type())) {
            return;
        }
        BigDecimal price = event.price() != null ? event.price() : runtime.currentPrice();
        if (price == null || price.signum() <= 0) {
            return;
        }
        if (runtime.position != null && !runtime.exitCoordinating.get()) {
            updateRuntimeExtrema(runtime, price);
        }
        StrategyContext context = buildContext(runtime, price, event);
        if (decisionLogs != null && runtime.plugin instanceof KlineSlopeRateStrategy) {
            var slope = KlineSlopeRateStrategy.calculateSlope(context, runtime.params);
            if (slope == null || !slope.entryApproved()) {
                runtime.decisionLogCursor.reset();
            }
        }
        if (log.isDebugEnabled() && System.currentTimeMillis() >= runtime.nextLatencyLogAt) {
            log.debug("evaluate 到达遥测 instanceId={} symbol={} eventType={} price={} prevKlineOpen={} klineOpen={} klineClosed={}", runtime.instance.getId(),
            runtime.symbol.getSymbol(), event.type(), price, context.previousKlineOpenPrice(), context.klineOpenTime(), context.klineClosed());
        }
        publishSlopeTelemetry(runtime, context);
        // 待确认交易或退出协调会暂停本轮开仓、退出和保护同步；实时斜率仍随有效行情更新。
        if (runtime.hasPendingAction() || runtime.exitCoordinating.get()) {
            logEntryDecision(runtime, context, "PENDING_ACTION", "存在待确认交易或退出协调尚未完成，等待状态恢复");
            log.warn("策略决策暂停：存在待确认交易或退出协调尚未完成 instanceId={} symbol={} pendingAction={} exitCoordinating={}",
                runtime.instance.getId(), runtime.symbol.getSymbol(), runtime.hasPendingAction(), runtime.exitCoordinating.get());
            return;
        }
        if (runtime.position == null) {
            if (runtime.positionReconciling) {
                logEntryDecision(runtime, context, "POSITION_RECONCILING", "正在核实交易所持仓");
                long now = System.currentTimeMillis();
                if (now >= runtime.nextPositionReconcilingLogAt) {
                    runtime.nextPositionReconcilingLogAt = now + 30_000L;
                    log.warn("开仓暂停：正在核实交易所持仓 instanceId={} symbol={}", runtime.instance.getId(), runtime.symbol.getSymbol());
                }
                return;
            }
            if (!"RUNNING".equals(runtime.instance.getStatus())) {
                logEntryDecision(runtime, context, "INSTANCE_NOT_RUNNING", "策略实例未处于 RUNNING 状态，是：" + runtime.instance.getStatus());
                log.warn("开仓暂停：策略实例状态不是 RUNNING instanceId={} symbol={} status={}", runtime.instance.getId(), runtime.symbol.getSymbol(), runtime.instance.getStatus());
                return;
            }
            if (runtime.state != null && (Boolean.TRUE.equals(runtime.state.getRecoveryRequired()) || Boolean.TRUE.equals(runtime.state.getUnsafeRecovery()))) {
                logEntryDecision(runtime, context, "RECOVERY_REQUIRED", "遗留交易状态尚未恢复核实");
                log.warn("计算是否产生开仓信号前判断问题3：遗留交易状态尚未恢复核实 instanceId={} credentialId={} symbol={} recoveryRequired={} unsafeRecovery={} positionState={} positionQty={}",
                    runtime.instance.getId(), runtime.credential.getId(), runtime.symbol.getSymbol(), runtime.state.getRecoveryRequired(), runtime.state.getUnsafeRecovery(), runtime.state.getStatus(),
                    runtime.state.getPositionQty());
                return;
            }
            if (runtime.state != null && !"CLOSED".equals(runtime.state.getStatus()) && runtime.state.getPositionQty() != null && runtime.state.getPositionQty().signum() > 0) {
                logEntryDecision(runtime, context, "LOCAL_POSITION_PENDING", "本地存在尚未确认关闭的持仓");
                log.warn("计算是否产生开仓信号前判断问题4：{}，交易对：{}", "本地存在尚未确认关闭的持仓", runtime.symbol);
                return;
            }
            evaluateEntry(runtime, context);
        } else {
            logEntryDecision(runtime, context, "POSITION_OPEN", "该策略实例已有持仓，不重复开仓");
            log.warn("持仓处理：跳过开仓并检查退出条件和保护单 instanceId={} symbol={}", runtime.instance.getId(), runtime.symbol.getSymbol());
            // 先处理已经越过阈值的本地退出，避免先挂已穿价的条件单。
            evaluateExit(runtime, context);
            if (runtime.position != null && !runtime.hasPendingAction() && System.currentTimeMillis() >= runtime.nextProtectionSyncAt) {
                runtime.nextProtectionSyncAt = System.currentTimeMillis() + 2000;
                syncPluginProtections(runtime, context);
            }
        }
    }

    /**
     * 根据当前市场上下文计算是否产生开仓信号。
     */
    private void evaluateEntry(ActiveRuntime runtime, StrategyContext context) {
        // FIXED: 策略/凭证/交易对被停用后仅保留保护，不再允许开仓。
        if (runtime.protectionOnly) {
            logEntryDecision(runtime, context, "PROTECTION_ONLY", "策略已停止开仓，仅保留持仓保护");
            return;
        }
        if (runtime.hasPendingAction() || runtime.state != null && (Boolean.TRUE.equals(runtime.state.getRecoveryRequired()) || Boolean.TRUE.equals(runtime.state.getUnsafeRecovery()))) {
            return;
        }
        if (context.klineOpenTime() == null) {
            logEntryDecision(runtime, context, "KLINE_TIME_MISSING", "缺少当前 K 线时间");
            return;
        }
        if (runtime.protectionSyncing.get() || runtime.protectionCleanupPending) {
            logEntryDecision(runtime, context, "PROTECTION_PENDING", "保护单同步或清理尚未完成");
            return;
        }
        if (runtime.protectionDegraded) {
            logEntryDecision(runtime, context, "PROTECTION_UNCONFIRMED", "当前持仓保护单未全部确认，禁止开仓");
            return;
        }
        Optional<EntrySignal> signal = runtime.plugin.evaluateEntry(context, runtime.params);
        if (signal.isEmpty()) {
            return;
        }

        if (!runtime.entryRetryGate.due(System.currentTimeMillis())) {
            return;
        }

        BigDecimal openRatio = runtime.strategySymbol.getOpenRatio() == null ? runtime.strategy.getDefaultOpenRatio() : runtime.strategySymbol.getOpenRatio();
        int leverage = runtime.strategySymbol.getLeverage() == null ? runtime.strategy.getDefaultLeverage() : runtime.strategySymbol.getLeverage();
        if (openRatio == null || openRatio.signum() <= 0 || openRatio.compareTo(BigDecimal.ONE) > 0 || leverage <= 0) {
            logEntryDecision(runtime, context, "INVALID_OPEN_CONFIG", "开仓比例或杠杆无效");
            log.warn("策略开仓跳过：开仓比例或杠杆无效 instanceId={} symbol={}", runtime.instance.getId(), runtime.symbol.getSymbol());
            return;
        }

        Integer maxOpenCount = runtime.strategy.getMaxOpenCount();
        if (maxOpenCount != null && maxOpenCount > 0) {
            Long openCount;
            try {
                // FIXED: 统计状态包含 OPEN_PENDING，避免并发下突破最大开仓数。
                openCount = runtimeLedgerMapper.countStrategyExposures(runtime.strategy.getId(), runtime.credential.getId());
                if (openCount == null) {
                    throw new org.springframework.dao.DataRetrievalFailureException("Strategy exposure count unavailable");
                }
            } catch (org.springframework.dao.DataAccessException ex) {
                // 持仓事实不可读时必须停止本次开仓，不能在数据库连接异常期间放行交易。
                logEntryDecision(runtime, context, "POSITION_COUNT_UNAVAILABLE", "最大持仓数查询失败，禁止放行");
                log.warn("策略开仓跳过：最大持仓数查询失败 instanceId={} symbol={} error={}", runtime.instance.getId(), runtime.symbol.getSymbol(), ex.getMessage());
                return;
            }
            if (openCount != null && openCount >= maxOpenCount) {
                logEntryDecision(runtime, context, "MAX_POSITIONS", "达到策略最大持仓数");
                log.warn("策略开仓跳过：达到最大持仓数 instanceId={} symbol={} openCount={} maxOpenCount={}", runtime.instance.getId(), runtime.symbol.getSymbol(), openCount, maxOpenCount);
                logExposureDiagnostic(runtime, openCount, maxOpenCount);
                return;
            }
        }

        // 先保留并核实旧关闭事实，不能等 OPEN_PENDING 覆盖后才尝试释放历史预留。
        if (runtime.state != null && "CLOSED".equals(runtime.state.getStatus()) && !runtime.hasPendingAction()) {
            try {
                executionService.releaseClosedOpenReservationsForRuntime(runtime.credential.getId(), runtime.instance.getId(), runtime.symbol.getSymbol(),
                 runtime.credential.getRunMode());
            } catch (RuntimeException ex) {
                logEntryDecision(runtime, context, "CLOSED_RESERVATION_UNAVAILABLE", org.dromara.quant.decision.TradeDecisionLogService.safeReason(ex));
                log.warn("策略开仓等待历史预留核实 instanceId={} symbol={} reason={}", runtime.instance.getId(), runtime.symbol.getSymbol(), ex.getMessage());
                return;
            }
        }

        boolean paper = "PAPER".equalsIgnoreCase(runtime.credential.getRunMode());
        BigDecimal availableBalance;
        org.dromara.quant.market.AccountBalanceSnapshot balanceSnapshot;
        if (paper) {
            balanceSnapshot = null;
            availableBalance = paperTradingService.availableBalance(runtime.credential.getId());
        } else {
            try {
                balanceSnapshot = balanceCache.requireSnapshot(runtime.credential.getId());
                availableBalance = openCapacityService.availableBalance(runtime.credential.getId(), balanceSnapshot);
                preparationService.requireReady(runtime.credential.getId(), runtime.symbol.getSymbol(), leverage, effectiveMarginMode(runtime));
            } catch (RuntimeException ex) {
                logEntryDecision(runtime, context, "ACCOUNT_NOT_READY", org.dromara.quant.decision.TradeDecisionLogService.safeReason(ex));
                log.debug("策略开仓等待账户余额或交易设置校准 instanceId={} symbol={} reason={}", runtime.instance.getId(), runtime.symbol.getSymbol(), ex.getMessage());
                if (ex instanceof LiveTradingGate.LiveTradingBlockedException) {
                    notifyPreparationBlocked(runtime, ex.getMessage());
                }
                return;
            }
        }
        if (availableBalance == null || availableBalance.signum() <= 0) {
            logEntryDecision(runtime, context, "INSUFFICIENT_BALANCE", "可用余额不足");
            log.debug("策略开仓跳过：可用余额不足 instanceId={} symbol={}", runtime.instance.getId(), runtime.symbol.getSymbol());
            return;
        }

        BigDecimal targetMargin = availableBalance.multiply(openRatio);
        BigDecimal targetNotional = targetMargin.multiply(BigDecimal.valueOf(leverage));
        BigDecimal quantity = targetNotional.divide(context.currentPrice(), 16, RoundingMode.DOWN);
        if (quantity.signum() <= 0) {
            logEntryDecision(runtime, context, "ZERO_QUANTITY", "下单数量为零");
            log.debug("策略开仓跳过：下单数量为零 instanceId={} symbol={}", runtime.instance.getId(), runtime.symbol.getSymbol());
            return;
        }
        if (runtime.protectionRule != null) {
            try {
                quantity = org.dromara.quant.exchange.QuantityNormalizer.quantity(quantity, runtime.protectionRule, "MARKET");
                if (runtime.plugin instanceof KlineSlopeRateStrategy) {
                    org.dromara.quant.exchange.QuantityNormalizer.quantity(quantity.multiply(KlineSlopeRateStrategy.firstClosePositionRate(runtime.params)),
                     runtime.protectionRule, "MARKET");
                }
            } catch (IllegalArgumentException ex) {
                logEntryDecision(runtime, context, "PROTECTION_QUANTITY_INVALID", "开仓或首次止盈数量不满足交易步长：" + ex.getMessage());
                return;
            }
        }
        BigDecimal margin = quantity.multiply(context.currentPrice()).divide(BigDecimal.valueOf(leverage), 16, RoundingMode.HALF_UP);
        if (margin.signum() <= 0) {
            logEntryDecision(runtime, context, "INVALID_MARGIN", "计算保证金非正数");
            log.warn("策略开仓跳过：计算保证金非正数 instanceId={} symbol={} margin={} quantity={} currentPrice={} leverage={}", runtime.instance.getId(), runtime.symbol.getSymbol(), margin,
             quantity, context.currentPrice(), leverage);
            return;
        }
        BigDecimal retryQuantity = quantity;
        try {
            if (!runtime.entryRetryGate.allow(System.currentTimeMillis(), () -> executionService.requireOpenCapacityForRuntime(runtime.credential.getId(),
             runtime.strategy.getId(), runtime.instance.getId(), runtime.symbol.getSymbol(), signal.get().direction() == EntrySignal.Direction.LONG ? "BUY" : "SELL",
        retryQuantity, context.currentPrice(), leverage, runtime.credential.getRunMode(), balanceSnapshot))) {
                return;
            }
        } catch (RuntimeException ex) {
            logEntryDecision(runtime, context, "RETRY_STATE_UNAVAILABLE", org.dromara.quant.decision.TradeDecisionLogService.safeReason(ex));
            return;
        }

        // 资金与风控准入后再次验证原始行情时间，禁止按过期点位追单。
        if (!runtime.marketHealthy || !runtime.marketStreamReady || context.eventTime() == null || context.eventTime().isBefore(Instant.now().minusMillis(Math.max(1,
         properties.getMarket().getMaxEventAgeMillis())))) {
            logEntryDecision(runtime, context, "STALE_AFTER_ADMISSION", "资金准入后行情不可用或事件已过期");
            log.warn("策略开仓跳过：资金准入后行情不可用或事件已过期 instanceId={} symbol={} marketHealthy={} marketStreamReady={} eventTime={} maxEventAgeMillis={}", runtime.instance.getId(),
             runtime.symbol.getSymbol(), runtime.marketHealthy, runtime.marketStreamReady, context.eventTime(), Math.max(1, properties.getMarket().getMaxEventAgeMillis()));
            return;
        }
        // 仅在开仓前置条件满足后占用本根 K 线；余额或持仓条件暂不满足时允许后续行情重新评估。
        if (!entryGuardService.tryClaim(runtime.instance.getId(), runtime.credential.getId(), runtime.symbol.getSymbol(), context.klineOpenTime())) {
            logEntryDecision(runtime, context, "KLINE_ALREADY_CLAIMED", "本根 K 线已占用开仓保护记录");
            log.error("策略开仓跳过：本根K线已占用开仓保护记录 instanceId={} symbol={} klineOpenTime={}", runtime.instance.getId(), runtime.symbol.getSymbol(), context.klineOpenTime());
            return;
        }

        String direction = signal.get().direction().name();
        String side = signal.get().direction() == EntrySignal.Direction.LONG ? "BUY" : "SELL";
        String idempotencyKey = buildIdempotencyKey(runtime, "OPEN", context.klineOpenTime());
        QuantExecutionRecord previousAttempt = executionRecordService.find(runtime.credential.getId(), idempotencyKey);
        if (previousAttempt != null && "FAILED".equals(previousAttempt.getExecutionStatus())) {
            idempotencyKey += ":" + UUID.randomUUID();
        }
        QuantStrategyPositionState state;
        try {
            state = preparePendingStateForOpen(runtime, direction, quantity, context.currentPrice(), context.klineOpenTime(), idempotencyKey);
        } catch (RuntimeException ex) {
            recordTradeDecision(runtime, context, "OPEN", signal.get().direction().name(), quantity, margin, idempotencyKey, "FAILED", "PENDING_STATE_FAILED",
                org.dromara.quant.decision.TradeDecisionLogService.safeReason(ex), null, false);
            entryGuardService.release(runtime.instance.getId(), runtime.credential.getId(), runtime.symbol.getSymbol(), context.klineOpenTime());
            throw ex;
        }
        runtime.state = state;
        recordTradeDecision(runtime, context, "OPEN", direction, quantity, margin, idempotencyKey, "REQUESTED", "SLOPE_ENTRY", signal.get().reason(), null, false);
        runtime.lastEntryAttemptEventTime = context.eventTime();
        runtime.lastEntryAttemptPrice = context.currentPrice();
        boolean marginReserved = false;
        ExecutionResult executionResult = null;
        try {
            if (paper) {
                paperTradingService.reserveMargin(runtime.credential.getId(), margin);
                marginReserved = true;
            }
            executionResult = executionService.openByRuntime(runtime.credential.getId(), runtime.strategy.getId(), runtime.instance.getId(), runtime.symbol.getSymbol(), side,
             null, quantity, context.currentPrice(), leverage, effectiveMarginMode(runtime), idempotencyKey, context.eventTime());
            recordTradeDecision(runtime, context, "OPEN", direction, quantity, margin, idempotencyKey, "ACCEPTED", "EXECUTION_RESULT", signal.get().reason(), executionResult,
             false);
            runtime.entryRetryGate.reset();
            entryGuardService.bindOrder(runtime.instance.getId(), runtime.credential.getId(), runtime.symbol.getSymbol(), context.klineOpenTime(), executionResult.orderId());
            state.setPendingOrderId(executionResult.orderId());
            state.setPendingClientOrderId(executionResult.clientOrderId());
            runtime.state = state;
            runtime.instance.setLastSignalAt(LocalDateTime.now());
            instanceMapper.updateById(runtime.instance);
            if (paper) {
                runtime.position = paperTradingService.recordOpenFill(runtime.credential.getId(), idempotencyKey, runtime.symbol.getSymbol(), direction, quantity,
                 context.currentPrice(), leverage);
                recordTradeDecision(runtime, context, "OPEN", direction, quantity, margin, idempotencyKey, "FILLED", "EXECUTION_RESULT", signal.get().reason(), executionResult,
                 true);
            } else {
                runtime.position = loadPosition(runtime.credential, runtime.symbol.getSymbol(), executionService.toCredential(runtime.credential));
            }
            if (runtime.position == null) {
                state.setRecoveryRequired(true);
                state.setUnsafeRecovery(!paper);
                stateService.save(state);
                return;
            }
            state.setPositionId(runtime.position.getId());
            state.setPositionQty(runtime.position.getQuantity());
            state.setEntryPrice(runtime.position.getEntryPrice());
            state.setInitialPositionQty(runtime.position.getQuantity());
            ExchangeGateway.OrderSnapshot filledOpen = null;
            if (!paper) {
                filledOpen = gateway.queryOrder(executionService.toCredential(runtime.credential), runtime.symbol.getSymbol(), executionResult.orderId(),
                 executionResult.clientOrderId());
                if (filledOpen == null || !"FILLED".equalsIgnoreCase(filledOpen.status()) || filledOpen.executedQuantity() == null || filledOpen.executedQuantity().signum() <= 0 || runtime.position.getQuantity().compareTo(filledOpen.executedQuantity()) > 0 || !direction.equalsIgnoreCase(runtime.position.getPositionSide())) {
                    state.setRecoveryRequired(true);
                    state.setUnsafeRecovery(true);
                    stateService.save(state);
                    return;
                }
                state.setInitialPositionQty(filledOpen.executedQuantity());
            }
            state.setInitialMargin(runtime.position.getMargin());
            freezeFilledProtection(runtime);
            freezeOrderEntryPrice(state, filledOpen);
            state.setStatus("OPEN");
            if (state.getOpenedAt() == null) {
                state.setOpenedAt(LocalDateTime.now());
            }
            clearPending(state);
            stateService.save(state);
            runtime.state = state;
            StrategyContext openedContext = buildContext(runtime, context.currentPrice(), new ExchangeGateway.MarketEvent("TRADE", runtime.symbol.getSymbol(),
            runtime.strategySymbol.getIntervalCode(), context.currentPrice(), null, context.eventTime()));
            syncPluginProtections(runtime, openedContext);   // 内部会 requestProtectionSync(runtime, true)
            notificationService.notifyOpen(runtime.strategy, runtime.credential, runtime.symbol.getSymbol(), direction, runtime.position.getQuantity(),
             runtime.position.getEntryPrice(), leverage, openRatio, signal.get().reason());
            log.info("实时策略开仓 instanceId={} symbol={} direction={} price={} quantity={} reason={}", runtime.instance.getId(), runtime.symbol.getSymbol(), direction,
             context.currentPrice(), quantity, signal.get().reason());
        } catch (RuntimeException ex) {
            QuantExecutionRecordStatus status = findExecutionStatus(runtime.credential.getId(), idempotencyKey);
            if (executionResult != null && executionResult.accepted()) {
                state.setRecoveryRequired(true);
                state.setUnsafeRecovery(!paper);
                stateService.save(state);
            } else if ("FAILED".equals(status.value())) {
                entryGuardService.release(runtime.instance.getId(), runtime.credential.getId(), runtime.symbol.getSymbol(), context.klineOpenTime());
                if (paper && marginReserved) {
                    releasePaperMargin(runtime.credential.getId(), margin);
                }
                stateService.markClosed(state);
                boolean waitForChange = ex instanceof OpenCapacityService.CapacityBlockedException blocked && blocked.waitForCapacityChange();
                runtime.entryRetryGate.failed(System.currentTimeMillis(), waitForChange);
                if (ex instanceof LiveTradingGate.LiveTradingBlockedException || ex instanceof org.dromara.quant.risk.OpenCapacityService.CapacityBlockedException) {
                    notificationService.notifyLiveBlocked(runtime.credential, runtime.symbol.getSymbol(), ex.getMessage());
                }
            } else {
                state.setRecoveryRequired(true);
                state.setUnsafeRecovery(!paper);
                stateService.save(state);
            }
            String reason = ex instanceof ExchangeTradingPort.ExchangeRequestException || ex instanceof OpenCapacityService.CapacityBlockedException ? ex.getMessage() :
             ex.getClass().getSimpleName();
            runtime.instance.setLastError(reason);
            instanceMapper.updateById(runtime.instance);
            recordTradeDecision(runtime, context, "OPEN", direction, quantity, margin, idempotencyKey, "FAILED".equals(status.value()) ? "FAILED" : "UNKNOWN", "EXECUTION_FAILURE"
 , org.dromara.quant.decision.TradeDecisionLogService.safeReason(ex), executionResult, false);
            log.warn("策略开仓执行未完成，保留行情连接 instanceId={} symbol={} executionStatus={} reason={}", runtime.instance.getId(), runtime.symbol.getSymbol(), status.value(), reason);
            if (!"FAILED".equals(status.value())) {
                requestReconciliation(runtime);
            }
        }
    }

    private void logExposureDiagnostic(ActiveRuntime runtime, Long openCount, Integer maxOpenCount) {
        long now = System.currentTimeMillis();
        Long nextLogAt = nextExposureDiagnosticLogAt.get(runtime.instance.getId());
        if (nextLogAt != null && now < nextLogAt) {
            return;
        }
        nextExposureDiagnosticLogAt.put(runtime.instance.getId(), now + 60_000L);
        try {
            List<Map<String, Object>> exposures = runtimeLedgerMapper.findStrategyExposures(runtime.strategy.getId(), runtime.credential.getId());
            log.warn("策略最大持仓占用明细 instanceId={} symbol={} runMode={} strategyId={} credentialId={} openCount={} maxOpenCount={} exposures={}", runtime.instance.getId(),
             runtime.symbol.getSymbol(), runtime.credential.getRunMode(), runtime.strategy.getId(), runtime.credential.getId(), openCount, maxOpenCount, exposures);
        } catch (RuntimeException ex) {
            log.warn("策略最大持仓占用明细查询失败 instanceId={} strategyId={} credentialId={} error={}", runtime.instance.getId(), runtime.strategy.getId(), runtime.credential.getId(),
             ex.getMessage());
        }
    }

    /**
     * 记录经成交事实确认的持仓减少，不用估值变化推断平仓。
     */
    private void recordPositionDecrease(ActiveRuntime runtime, QuantPosition before, BigDecimal closedQuantity, String reason) {
        if (before == null || closedQuantity == null || closedQuantity.signum() <= 0) {
            return;
        }
        blockCloseCandle(runtime, runtime.lastEvaluatedKlineOpenTime);
        if (decisionLogs == null) {
            return;
        }
        try {
            BigDecimal remaining = runtime.position == null ? BigDecimal.ZERO : runtime.position.getQuantity();
            decisionLogs.record(org.dromara.quant.decision.DecisionLogEvent.builder().eventKey("P:" + runtime.credential.getId() + ":" + before.getId() + ":" + remaining).recordType("POSITION_CHANGE").credentialId(runtime.credential.getId()).accountName(runtime.credential.getAccountName()).runMode(runtime.credential.getRunMode()).strategyName("外部交易／策略未确认").symbol(runtime.symbol.getSymbol()).operation("CLOSE").direction(before.getPositionSide()).quantity(closedQuantity).status("POSITION_CONFIRMED").reasonCode("EXCHANGE_POSITION_CHANGE").reason(reason + "；持仓变化已确认，实际成交价格和操作来源以订单成交记录为准").eventTime(Instant.now()).build());
        } catch (RuntimeException ignored) {
        }
    }

    /** 持仓减少已由交易所事实确认后，持久化封锁发生减少的账户/交易对 K 线。 */
    private void blockCloseCandle(ActiveRuntime runtime, Instant klineOpenTime) {
        Instant candle = klineOpenTime == null ? runtime.lastEvaluatedKlineOpenTime : klineOpenTime;
        if (candle == null) {
            throw new IllegalStateException("无法确认平仓所属 K 线，禁止解除账户交易对开仓封锁");
        }
        entryGuardService.blockClosedCandle(runtime.instance.getId(), runtime.credential.getId(), runtime.symbol.getSymbol(), candle);
    }

    private void logUnavailableEntry(ActiveRuntime runtime, ExchangeGateway.MarketEvent event, String code, String reason) {
        if (decisionLogs == null || event.replay() || !("TRADE".equals(event.type()) || "KLINE".equals(event.type()))) {
            return;
        }
        BigDecimal price = event.price() == null ? runtime.currentPrice() : event.price();
        if (price != null && price.signum() > 0) {
            logEntryDecision(runtime, buildContext(runtime, price, event), code, reason);
        }
    }

    /**
     * 构造决策日志快照，只保存本次判断所需的行情与策略上下文。
     */
    private DecisionLogEvent.DecisionLogEventBuilder decisionSnapshot(ActiveRuntime runtime, StrategyContext context) {
        var slope = KlineSlopeRateStrategy.calculateSlope(context, runtime.params);
        return DecisionLogEvent.builder().credentialId(runtime.credential.getId()).accountName(runtime.credential.getAccountName()).runMode(runtime.credential.getRunMode()).strategyId(runtime.strategy.getId()).strategyInstanceId(runtime.instance.getId()).strategyName(runtime.strategy.getStrategyName()).symbol(runtime.symbol.getSymbol()).klineTime(context.klineOpenTime()).eventTime(context.eventTime() == null ? Instant.now() : context.eventTime()).price(context.currentPrice()).angle(slope == null ? null : slope.degrees()).threshold(slope == null ? null : slope.entryThreshold());
    }

    private void logEntryDecision(ActiveRuntime runtime, StrategyContext context, String code, String reason) {
        if (decisionLogs == null || !(runtime.plugin instanceof KlineSlopeRateStrategy)) {
            return;
        }
        try {
            var slope = KlineSlopeRateStrategy.calculateSlope(context, runtime.params);
            if (slope == null || !slope.entryApproved()) {
                runtime.decisionLogCursor.reset();
                return;
            }
            decisionLogs.record(decisionSnapshot(runtime, context).eventKey(runtime.decisionLogCursor.key(context.klineOpenTime(), code + ":" + reason.replaceAll("[+-]?\\d+(?:\\" +
             ".\\d+)?", "#"))).recordType("EVALUATION").operation("OPEN").direction(slope.rawDegrees() > 0 ? "LONG" : "SHORT").status("BLOCKED").reasonCode(code).reason(reason).build());
        } catch (RuntimeException ignored) { /* 日志不能影响策略。 */ }
    }

    /**
     * 统一记录开平仓判断结果，日志写入失败不得改变交易决策结果。
     */
    private void recordTradeDecision(ActiveRuntime runtime, StrategyContext context, String operation, String direction, BigDecimal quantity, BigDecimal margin,
    String executionKey, String status, String reasonCode, String reason, ExecutionResult result, boolean paperFill) {
        if (decisionLogs == null) {
            return;
        }
        try {
            decisionLogs.record(decisionSnapshot(runtime, context).eventKey(org.dromara.quant.decision.TradeDecisionLogRepository.executionKey(runtime.credential.getId(),
 executionKey)).recordType("EXECUTION").operation(operation).direction(direction).quantity(quantity).margin(margin).status(paperFill ? "FILLED" : status).reasonCode(reasonCode).reason(reason).idempotencyKey(executionKey).orderId(result == null ? null : result.orderId()).clientOrderId(result == null ? null : result.clientOrderId()).tradeTime(paperFill ? Instant.now() : null).build());
            runtime.decisionLogCursor.reset();
        } catch (RuntimeException ignored) { /* 日志不能影响交易。 */ }
    }

    /**
     * 后台发送准备阶段拒绝原因；数据库通知写入不得阻塞行情，按实例限频。
     */
    private void notifyPreparationBlocked(ActiveRuntime runtime, String reason) {
        long now = System.currentTimeMillis();
        if (now < runtime.nextPreparationNoticeAt) {
            return;
        }
        runtime.nextPreparationNoticeAt = now + 60_000L;
        Thread.ofVirtual().name("quant-preparation-notice").start(() -> notificationService.notifyLiveBlocked(runtime.credential, runtime.symbol.getSymbol(), reason));
    }

    /**
     * 推送策略插件实际使用的斜率结果；交易决策仍由插件原逻辑执行。
     */
    private void publishSlopeTelemetry(ActiveRuntime runtime, StrategyContext context) {
        if (!(runtime.plugin instanceof KlineSlopeRateStrategy)) {
            return;
        }
        KlineSlopeRateStrategy.SlopeEvaluation evaluation = KlineSlopeRateStrategy.calculateSlope(context, runtime.params);
        if (runtime.hasPendingAction() && System.nanoTime() >= runtime.nextPendingSlopeLogNanos) {
            runtime.nextPendingSlopeLogNanos = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            if (evaluation == null) {
                log.warn("策略实时斜率无法计算 instanceId={} symbol={} currentPrice={} previousKlineOpenPrice={} pendingAction={}", runtime.instance.getId(), context.symbol(),
context.currentPrice(), context.previousKlineOpenPrice(), runtime.state.getPendingAction());
            } else {
                log.info("策略实时斜率 +++ 时间：{} 交易对：{} 上根K线开盘价：{} 当前价：{} 坡度百分：{} 度数：{}° 开仓暂停 pendingAction={} instanceId={}", context.eventTime(), context.symbol(),
evaluation.referenceOpenPrice(), evaluation.currentPrice(), evaluation.slopePercent(), evaluation.degrees(), runtime.state.getPendingAction(),
                  runtime.instance.getId());
            }
        }
        if (evaluation == null) {
            long now = System.currentTimeMillis();
            if (now - runtime.nextSlopeDiagLogAt >= 30_000L) {
                runtime.nextSlopeDiagLogAt = now;
                log.warn("斜率计算返回 null instanceId={} symbol={} currentPrice={} previousKlineOpen={} klineOpen={} klineClosed={}", runtime.instance.getId(), context.symbol(),
                 context.currentPrice(), context.previousKlineOpenPrice(), context.klineOpenTime(), context.klineClosed());
            }
            return;
        }
        slopeTelemetryHub.publish(new StrategySlopeTelemetry(runtime.strategy.getId(), runtime.instance.getId(), runtime.credential.getId(), runtime.symbol.getSymbol(),
    runtime.strategySymbol.getIntervalCode(), evaluation.currentPrice(), evaluation.referenceOpenPrice(), evaluation.slopePercent(), evaluation.degrees(),
          evaluation.entryThreshold(), evaluation.entryApproved(), context.eventTime()));
    }

    /**
     * 冻结实际成交事实；参数快照和周期在发单前已经持久化。
     */
    private void freezeFilledProtection(ActiveRuntime runtime) {
        QuantStrategyPositionState state = runtime.state;
        if (state.getPositionCycleKey() == null) {
            state.setRecoveryRequired(true);
            state.setUnsafeRecovery(true);
            throw new IllegalStateException("历史持仓缺少周期及冻结事实，保留现有保护，需人工核查");
        }
        state.setEntryPrice(runtime.position.getEntryPrice());
        if (runtime.position.getLeverage() != null && runtime.position.getLeverage() > 0) {
            state.setFrozenLeverage(runtime.position.getLeverage());
        }
        state.setHighestPriceSinceEntry(state.getEntryPrice());
        state.setLowestPriceSinceEntry(state.getEntryPrice());
        runtime.position.setProtectionCycleKey(state.getPositionCycleKey());
        if (runtime.plugin instanceof KlineSlopeRateStrategy) {
            BigDecimal target = state.getInitialPositionQty().multiply(KlineSlopeRateStrategy.firstClosePositionRate(frozenParams(runtime)));
            if (runtime.protectionRule != null) {
                target = org.dromara.quant.exchange.QuantityNormalizer.quantity(target, runtime.protectionRule, "MARKET");
            }
            if (target.signum() <= 0) {
                throw new IllegalStateException("首次止盈数量低于交易规则最小步长，需人工核查");
            }
            state.setFirstTakeProfitTargetQty(target);
        }
    }

    /**
     * 判断保护状态是否已有不可逆成交事实，避免再按旧周期覆盖。
     */
    private boolean hasFrozenProtectionFacts(ActiveRuntime runtime) {
        return !(runtime.plugin instanceof KlineSlopeRateStrategy) || runtime.state != null && runtime.state.getPositionCycleKey() != null && runtime.state.getFrozenLeverage() != null && runtime.state.getEntryPrice() != null && runtime.state.getFirstTakeProfitTargetQty() != null;
    }

    /**
     * 三层止损并存；插件仍只负责自身信号，不访问交易所。
     */
    private List<ProtectionSignal> protectionSignals(ActiveRuntime runtime, StrategyContext context, Map<String, Object> params) {
        List<ProtectionSignal> signals = new ArrayList<>(runtime.plugin.evaluateProtection(context, params));
        if (runtime.plugin instanceof KlineSlopeRateStrategy && runtime.state != null && runtime.state.getFrozenSymbolStopLossRatio() != null) {
            signals.add(new ProtectionSignal("SYMBOL_STOP", "STOP_MARKET", BigDecimal.ONE, ProtectionSignal.QuantityBase.CURRENT,
KlineSlopeRateStrategy.priceForReturn(context.entryPrice(), context.direction(), runtime.state.getFrozenSymbolStopLossRatio().negate(), context.leverage()),
"交易对绑定止损"));
        }
        return signals;
    }

    /**
     * 本地按与 Binance 相同的规范化触发价格判断，方向和杠杆均来自开仓冻结事实。
     */
    private List<ExitSignal> protectionExits(ActiveRuntime runtime, StrategyContext context) {
        if (!(runtime.plugin instanceof KlineSlopeRateStrategy)) {
            return runtime.plugin.evaluateExit(context, frozenParams(runtime));
        }
        if (!hasFrozenProtectionFacts(runtime)) {
            return List.of();
        }
        List<ProtectionSignal> signals = protectionSignals(runtime, context, frozenParams(runtime));
        BigDecimal hard = resolveHardStopRatio(runtime);
        if (hard != null) {
            signals.addFirst(new ProtectionSignal("HARD_STOP", "STOP_MARKET", BigDecimal.ONE, ProtectionSignal.QuantityBase.CURRENT,
             KlineSlopeRateStrategy.priceForReturn(context.entryPrice(), context.direction(), hard.negate(), context.leverage()), "策略默认止损"));
        }
        for (ProtectionSignal signal : signals) {
            BigDecimal trigger = runtime.protectionRule == null ? signal.triggerPrice() : org.dromara.quant.exchange.QuantityNormalizer.price(signal.triggerPrice(),
            runtime.protectionRule);
            boolean shortPosition = "SHORT".equalsIgnoreCase(context.direction());
            boolean profit = "TAKE_PROFIT_MARKET".equals(signal.orderType());
            boolean triggered = shortPosition == profit ? context.currentPrice().compareTo(trigger) <= 0 : context.currentPrice().compareTo(trigger) >= 0;
            if (!triggered) {
                continue;
            }
            String type = switch (signal.type()) {
                case "LOCAL_STOP" -> KlineSlopeRateStrategy.EXIT_LOCAL_STOP;
                case "FIRST_TP" -> KlineSlopeRateStrategy.EXIT_FIRST_TP;
                case "STEP_TP" -> KlineSlopeRateStrategy.EXIT_STEP_TP;
                default -> signal.type() + "_LOSS";
            };
            return List.of(new ExitSignal(type, signal.quantityRate(), "本地镜像保护触发，规范化价格=" + trigger, "FIRST_TP".equals(signal.type()), signal.type(),
                "FIRST_TP".equals(signal.type()) ? ExitSignal.QuantityBase.INITIAL : ExitSignal.QuantityBase.CURRENT));
        }
        return List.of();
    }

    /**
     * 根据当前持仓和实时价格计算退出信号。
     */
    private void evaluateExit(ActiveRuntime runtime, StrategyContext context) {
        synchronized (runtime) {
            List<ExitSignal> evaluated = protectionExits(runtime, context);
            if (runtime.position == null || runtime.hasPendingAction() || runtime.reconciling.get() || runtime.positionReconciling || evaluated.isEmpty() || !runtime.exitCoordinating.compareAndSet(false, true)) {
                return;
            }
            String cycle = runtime.state == null ? null : runtime.state.getPositionCycleKey();
            runtime.exitTask = recoveryExecutor.submit(() -> {
                AtomicBoolean renewing = new AtomicBoolean(true);
                AtomicBoolean leaseHeld = new AtomicBoolean(true);
                Thread heartbeat = Thread.ofVirtual().name("quant-exit-lease").start(() -> {
                    try {
                        while (renewing.get()) {
                            Thread.sleep(5000);
                            if (renewing.get() && !leaseService.acquireOrRenew(runtime.instance.getId(), runtime.credential.getId(), runtime.symbol.getSymbol())) {
                                leaseHeld.set(false);
                                break;
                            }
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    } catch (RuntimeException ex) {
                        leaseHeld.set(false);
                    }
                });
                try {
                    if (!"PAPER".equalsIgnoreCase(runtime.credential.getRunMode())) {
                        Thread.sleep(Math.max(0, properties.getProtection().getConfirmationWaitMillis()));
                    }
                    java.util.concurrent.Future<?> inFlightProtection = runtime.protectionTask;
                    if (inFlightProtection != null) {
                        try {
                            inFlightProtection.get(Math.max(1, properties.getProtection().getSyncWaitMillis()), java.util.concurrent.TimeUnit.MILLISECONDS);
                        } catch (java.util.concurrent.ExecutionException ex) { /* 失败事实由保护任务记录，兜底继续精确核验。 */ } catch (java.util.concurrent.TimeoutException ex) {
                            // 请求可能仍在交易所执行；不能取消任务后假定未发送，也不能并发补平。
                            throw new IllegalStateException("在途保护任务超时，释放退出协调并等待精确对账", ex);
                        }
                    }
                    synchronized (runtime) {
                        if (runtime.closed || runtime.state == null || !Objects.equals(cycle, runtime.state.getPositionCycleKey()) || runtime.hasPendingAction() || runtime.position == null) {
                            return;
                        }
                    }
                    if (!leaseHeld.get() || !leaseService.acquireOrRenew(runtime.instance.getId(), runtime.credential.getId(), runtime.symbol.getSymbol())) {
                        return;
                    }
                    // 触发事实已经锁定；确认窗口中即便价格反弹仍完成该保护意图，不追用旧信号开仓。
                    executeProtectedExit(runtime, context, leaseHeld);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException ex) {
                    synchronized (runtime) {
                        if (!runtime.closed && runtime.state != null) {
                            runtime.state.setRecoveryRequired(true);
                            runtime.state.setUnsafeRecovery(true);
                            stateService.save(runtime.state);
                        }
                    }
                    log.error("本地保护兜底等待恢复 instanceId={} symbol={}", runtime.instance.getId(), runtime.symbol.getSymbol(), ex);
                } finally {
                    renewing.set(false);
                    heartbeat.interrupt();
                    boolean interrupted = false;
                    for (; ; ) {
                        try {
                            heartbeat.join();
                            break;
                        } catch (InterruptedException ex) {
                            interrupted = true;
                        }
                    }
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    synchronized (runtime) {
                        if (runtime.position != null && runtime.state != null && Objects.equals(cycle, runtime.state.getPositionCycleKey())) {
                            if (runtime.exitWindowHigh != null) {
                                updateRuntimeExtrema(runtime, runtime.exitWindowHigh);
                            }
                            if (runtime.exitWindowLow != null) {
                                updateRuntimeExtrema(runtime, runtime.exitWindowLow);
                            }
                        }
                        runtime.exitWindowHigh = null;
                        runtime.exitWindowLow = null;
                        runtime.exitCoordinating.set(false);
                        if (!runtime.closed && runtime.position != null && !runtime.hasPendingAction()) {
                            requestProtectionSync(runtime, true);
                        }
                    }
                    requestReconciliation(runtime);
                }
            });
        }
    }

    /**
     * 在持仓租约和新鲜账户事实有效时提交保护退出。
     */
    private void executeProtectedExit(ActiveRuntime runtime, StrategyContext context, AtomicBoolean leaseHeld) {
        List<ExitSignal> exits = protectionExits(runtime, context);
        if (exits.isEmpty() || runtime.position == null || runtime.hasPendingAction()) {
            return;
        }
        ExitSignal exit = exits.get(0);
        // 部分退出不能与仍在挂载的快照保护并行，否则撤掉旧条件单后可能被异步重新挂回。
        if (exit.quantityRate() != null && exit.quantityRate().compareTo(BigDecimal.ONE) < 0 && runtime.protectionSyncing.get()) {
            return;
        }
        if (handleExchangeProtectedExit(runtime, context, exit)) {
            return;
        }
        BigDecimal rate = exit.quantityRate() == null ? BigDecimal.ONE : exit.quantityRate().min(BigDecimal.ONE).max(BigDecimal.ZERO);
        // FIXED: 首次止盈按初始总仓位比例计算；其它退出按当前仓位。
        BigDecimal baseQty;
        if (exit.quantityBase() == ExitSignal.QuantityBase.INITIAL && runtime.state != null && runtime.state.getInitialPositionQty() != null && runtime.state.getInitialPositionQty().signum() > 0) {
            baseQty = runtime.state.getInitialPositionQty();
        } else {
            baseQty = runtime.position.getQuantity();
        }
        BigDecimal quantity = exit.markFirstTakeProfitTriggered() && runtime.state.getFirstTakeProfitTargetQty() != null ?
         PositionProtectionState.remainingFirstProfit(runtime.state).min(runtime.position.getQuantity()) : baseQty.multiply(rate).min(runtime.position.getQuantity()).setScale(16
         , RoundingMode.DOWN);
        if (runtime.protectionRule != null) {
            quantity = org.dromara.quant.exchange.QuantityNormalizer.quantity(quantity, runtime.protectionRule, "MARKET");
        }
        if (quantity.signum() <= 0) {
            return;
        }
        String direction = runtime.position.getPositionSide();
        String side = "SHORT".equalsIgnoreCase(direction) ? "BUY" : "SELL";
        Instant klineOpenTime = context.klineOpenTime() == null ? Instant.ofEpochMilli(System.currentTimeMillis()) : context.klineOpenTime();
        String idempotencyKey = buildIdempotencyKey(runtime, exit.type(), klineOpenTime);
        if (!leaseHeld.get() || !leaseService.acquireOrRenew(runtime.instance.getId(), runtime.credential.getId(), runtime.symbol.getSymbol())) {
            throw new IllegalStateException("退出前租约失效，等待恢复");
        }
        QuantStrategyPositionState state = preparePendingStateForExit(runtime, exit.type(), quantity, klineOpenTime, idempotencyKey);
        runtime.state = state;
        // 先封锁平仓意图所在 K 线，覆盖平仓成交确认与持仓缓存刷新之间的竞态窗口。
        blockCloseCandle(runtime, klineOpenTime);
        recordTradeDecision(runtime, context, "CLOSE", direction, quantity, null, idempotencyKey, "REQUESTED", exit.type(), exit.reason(), null, false);
        BigDecimal entryPriceBeforeClose = runtime.position.getEntryPrice();
        String directionBeforeClose = runtime.position.getPositionSide();
        ExecutionResult executionResult = null;
        try {
            executionResult = executionService.closeByRuntime(runtime.credential.getId(), runtime.strategy.getId(), runtime.instance.getId(), runtime.symbol.getSymbol(), side,
             direction, quantity, idempotencyKey);
            recordTradeDecision(runtime, context, "CLOSE", direction, quantity, null, idempotencyKey, "ACCEPTED", "EXECUTION_RESULT", exit.reason(), executionResult, false);
            state.setPendingOrderId(executionResult.orderId());
            state.setPendingClientOrderId(executionResult.clientOrderId());
            runtime.state = state;
            if ("PAPER".equalsIgnoreCase(runtime.credential.getRunMode())) {
                PaperTradingService.PaperCloseSettlement settlement = paperTradingService.settlePaperExecution(runtime.credential.getId(), idempotencyKey,
                 runtime.position.getId(), quantity, context.currentPrice());
                recordTradeDecision(runtime, context, "CLOSE", direction, quantity, null, idempotencyKey, "FILLED", "EXECUTION_RESULT", exit.reason(), executionResult, true);
                runtime.position = settlement.position();
                blockCloseCandle(runtime, klineOpenTime);
                if (runtime.position == null) {
                    if (!cancelStaleProtection(runtime)) {
                        return;
                    }
                    stateService.markClosed(state);
                    runtime.position = null;
                    runtime.state = stateService.findByInstance(runtime.instance.getId());
                } else {
                    state.setPositionQty(runtime.position.getQuantity());
                    state.setStatus("OPEN");
                    if (exit.markFirstTakeProfitTriggered()) {
                        PositionProtectionState.confirmLocalFirstProfit(state, quantity);
                    }
                    clearPending(state);
                    stateService.save(state);
                }
            } else {
                BigDecimal previousQuantity = state.getPositionQty();
                runtime.position = loadPosition(runtime.credential, runtime.symbol.getSymbol(), executionService.toCredential(runtime.credential));
                if (executionResult == null || !"FILLED".equalsIgnoreCase(executionResult.status())) {
                    state.setStatus("EXIT_PENDING");
                    state.setRecoveryRequired(true);
                    state.setUnsafeRecovery(true);
                    stateService.save(state);
                    return;
                }
                blockCloseCandle(runtime, klineOpenTime);
                if (runtime.position == null) {
                    if (!cancelStaleProtection(runtime)) {
                        return;
                    }
                    stateService.markClosed(state);
                    runtime.position = null;
                    runtime.state = stateService.findByInstance(runtime.instance.getId());
                } else if (executionResult == null || !"FILLED".equalsIgnoreCase(executionResult.status()) || previousQuantity == null || runtime.position.getQuantity().compareTo(previousQuantity.subtract(quantity).max(BigDecimal.ZERO)) > 0) {
                    state.setStatus("EXIT_PENDING");
                    state.setRecoveryRequired(true);
                    state.setUnsafeRecovery(true);
                    stateService.save(state);
                    return;
                } else {
                    state.setStatus("OPEN");
                    state.setPositionQty(runtime.position.getQuantity());
                    if (exit.markFirstTakeProfitTriggered()) {
                        PositionProtectionState.confirmLocalFirstProfit(state, quantity);
                    }
                    clearPending(state);
                    state.setRecoveryRequired(false);
                    state.setUnsafeRecovery(false);
                    stateService.save(state);
                }
            }
            if (exit.markFirstTakeProfitTriggered() && runtime.state != null && runtime.position != null) {
                runtime.state.setPositionQty(runtime.position.getQuantity());
                runtime.state.setStatus("OPEN");
                stateService.save(runtime.state);
                ensureHardStop(runtime);
                syncPluginProtections(runtime, buildContext(runtime, context.currentPrice(), new ExchangeGateway.MarketEvent("TRADE", runtime.symbol.getSymbol(),
                    runtime.strategySymbol.getIntervalCode(), context.currentPrice(), null, context.eventTime())));
            }
            BigDecimal profitRate = leveragedReturnRate(runtime, directionBeforeClose, context.currentPrice(), entryPriceBeforeClose);
            BigDecimal profitAmount = calculateProfitAmount(directionBeforeClose, context.currentPrice(), entryPriceBeforeClose, quantity);
            notificationService.notifyClose(runtime.strategy, runtime.credential, runtime.symbol.getSymbol(), directionBeforeClose, quantity, entryPriceBeforeClose,
             context.currentPrice(), profitAmount, profitRate, exit.type(), exit.reason());
            log.info("实时策略平仓 instanceId={} symbol={} type={} price={} quantity={} reason={}", runtime.instance.getId(), runtime.symbol.getSymbol(), exit.type(),
            context.currentPrice(), quantity, exit.reason());
        } catch (RuntimeException ex) {
            QuantExecutionRecordStatus status = findExecutionStatus(runtime.credential.getId(), idempotencyKey);
            recordTradeDecision(runtime, context, "CLOSE", direction, quantity, null, idempotencyKey, "FAILED".equals(status.value()) ? "FAILED" : "UNKNOWN", "EXECUTION_FAILURE"
                , org.dromara.quant.decision.TradeDecisionLogService.safeReason(ex), executionResult, false);
            if (executionResult == null && "FAILED".equals(status.value())) {
                state.setStatus("OPEN");
                clearPending(state);
            } else {
                state.setStatus("EXIT_PENDING");
                state.setRecoveryRequired(true);
                state.setUnsafeRecovery(!"PAPER".equalsIgnoreCase(runtime.credential.getRunMode()));
            }
            stateService.save(state);
            throw ex;
        }
    }

    /**
     * 对已经挂载 Binance 条件单的退出信号，本地 MARKET 仅作为 fallback：
     * 保护单仍在交易所工作时不重复平仓；保护单已经触发时先以交易所持仓事实更新本地状态。
     */
    private boolean handleExchangeProtectedExit(ActiveRuntime runtime, StrategyContext context, ExitSignal exit) {
        boolean live = !"PAPER".equalsIgnoreCase(runtime.credential.getRunMode());
        if (!live || exit.exchangeProtectionType() == null || exit.exchangeProtectionType().isBlank() || runtime.position == null) {
            return false;
        }
        runtime.position.setProtectionCycleKey(runtime.state.getPositionCycleKey());
        if (!protectionOrderService.quiesceCycleProtections(runtime.credential.getId(), runtime.position)) {
            runtime.state.setRecoveryRequired(true);
            runtime.state.setUnsafeRecovery(true);
            stateService.save(runtime.state);
            return true;
        }
        BigDecimal firstExecuted = protectionOrderService.firstTakeProfitExecutedQuantity(runtime.credential.getId(), runtime.position);

        QuantPosition before = runtime.position;
        QuantPosition refreshed;
        try {
            refreshed = loadPosition(runtime.credential, runtime.symbol.getSymbol(), executionService.toCredential(runtime.credential));
        } catch (RuntimeException ex) {
            // 保护单状态与持仓事实都无法确认时绝不盲目再发一笔减仓单，等待 REST/UserData 恢复。
            if (runtime.state != null) {
                runtime.state.setRecoveryRequired(true);
                stateService.save(runtime.state);
            }
            return true;
        }
        if (refreshed == null) {
            blockCloseCandle(runtime, context.klineOpenTime());
            runtime.position = null;
            recordPositionDecrease(runtime, before, before.getQuantity(), "持仓关闭；本地同时满足退出条件：" + exit.reason());
            if (!cancelStaleProtection(runtime)) {
                return true;
            }
            if (runtime.state != null) {
                stateService.markClosed(runtime.state);
                runtime.state = stateService.findByInstance(runtime.instance.getId());
            }
            BigDecimal closePrice = context.currentPrice();
            BigDecimal profitRate = leveragedReturnRate(runtime, before.getPositionSide(), closePrice, before.getEntryPrice());
            BigDecimal profitAmount = calculateProfitAmount(before.getPositionSide(), closePrice, before.getEntryPrice(), before.getQuantity());
            notificationService.notifyClose(runtime.strategy, runtime.credential, runtime.symbol.getSymbol(), before.getPositionSide(), before.getQuantity(),
             before.getEntryPrice(), closePrice, profitAmount, profitRate, "EXCHANGE_" + exit.type(), "Binance 条件单已执行并关闭持仓：" + exit.reason());
            return true;
        }
        BigDecimal totalFirst = firstExecuted.add(runtime.state.getFirstTakeProfitLocalExecutedQty() == null ? BigDecimal.ZERO :
runtime.state.getFirstTakeProfitLocalExecutedQty());
        if (runtime.state.getInitialPositionQty() != null && refreshed.getQuantity().compareTo(runtime.state.getInitialPositionQty().subtract(totalFirst).max(BigDecimal.ZERO)) > 0) {
            runtime.state.setRecoveryRequired(true);
            runtime.state.setUnsafeRecovery(true);
            stateService.save(runtime.state);
            return true;
        }
        PositionProtectionState.confirmExchangeFirstProfit(runtime.state, firstExecuted);
        stateService.save(runtime.state);
        if (before.getQuantity() != null && refreshed.getQuantity() != null && refreshed.getQuantity().compareTo(before.getQuantity()) < 0) {
            BigDecimal closedQty = before.getQuantity().subtract(refreshed.getQuantity());
            recordPositionDecrease(runtime, before, closedQty, "保护条件单已确认成交，按剩余目标补平");

            BigDecimal closePrice = context.currentPrice() != null ? context.currentPrice() : (refreshed.getMarkPrice() != null ? refreshed.getMarkPrice() :
before.getEntryPrice());
            BigDecimal profitRate = leveragedReturnRate(runtime, before.getPositionSide(), closePrice, before.getEntryPrice());
            BigDecimal profitAmount = calculateProfitAmount(before.getPositionSide(), closePrice, before.getEntryPrice(), closedQty);

            notificationService.notifyClose(runtime.strategy, runtime.credential, runtime.symbol.getSymbol(), before.getPositionSide(), closedQty, before.getEntryPrice(),
                closePrice, profitAmount, profitRate, "EXCHANGE_" + exit.type(), "Binance 条件单已执行部分减仓：" + exit.reason());
        }
        if (before.getQuantity() != null && refreshed.getQuantity() != null && refreshed.getQuantity().compareTo(before.getQuantity()) > 0) {
            // 出现外部加仓/事实异常时不按旧数量继续 fallback，交给恢复链路处理。
            runtime.position = refreshed;
            if (runtime.state != null) {
                runtime.state.setRecoveryRequired(true);
                stateService.save(runtime.state);
            }
            return true;
        }
        runtime.position = refreshed;
        runtime.position.setProtectionCycleKey(runtime.state.getPositionCycleKey());
        runtime.state.setPositionQty(refreshed.getQuantity());
        if (exit.markFirstTakeProfitTriggered() && Boolean.TRUE.equals(runtime.state.getFirstTakeProfitTriggered())) {
            return true;
        }
        return false;
    }

    /**
     * 构建不暴露基础设施依赖的 StrategyContext 快照。
     */
    private StrategyContext buildContext(ActiveRuntime runtime, BigDecimal price, ExchangeGateway.MarketEvent event) {
        QuantPosition position = runtime.position;
        int leverage = runtime.state != null && runtime.state.getFrozenLeverage() != null ? runtime.state.getFrozenLeverage() : runtime.strategySymbol.getLeverage() == null ?
         runtime.strategy.getDefaultLeverage() : runtime.strategySymbol.getLeverage();
        return new StrategyContext(runtime.symbol.getSymbol(), position == null ? null : position.getPositionSide(), position == null ? null :
 runtime.state != null && runtime.state.getEntryPrice() != null ? runtime.state.getEntryPrice() : position.getEntryPrice(), price, runtime.market.referencePrice(),
runtime.market.previousKlineOpenPrice(), runtime.market.currentKlineOpen(), runtime.market.currentKlineClosed(), event.eventTime() == null ? Instant.now() :
           event.eventTime(), position == null ? BigDecimal.ZERO : position.getQuantity(), runtime.state == null || runtime.state.getInitialPositionQty() == null ?
            (position == null ? BigDecimal.ZERO : position.getQuantity()) : runtime.state.getInitialPositionQty(), position == null ? BigDecimal.ZERO : position.getMargin(),
             leverage, runtime.state == null ? null : runtime.state.getHighestPriceSinceEntry(), runtime.state == null ? null : runtime.state.getLowestPriceSinceEntry(),
            runtime.state != null && Boolean.TRUE.equals(runtime.state.getFirstTakeProfitTriggered()), runtime.state == null || runtime.state.getHighestStep() == null ? 0 :
               runtime.state.getHighestStep());
    }

    /**
     * 读取开仓时冻结的策略参数。
     */
    private Map<String, Object> frozenParams(ActiveRuntime runtime) {
        if (runtime.state == null || runtime.state.getStrategyParamsSnapshot() == null || runtime.state.getStrategyParamsSnapshot().isBlank()) {
            return runtime.params;
        }
        return parseParams(runtime.state.getStrategyParamsSnapshot());
    }

    /**
     * 在发送开仓执行前持久化待确认动作。
     */
    private QuantStrategyPositionState preparePendingStateForOpen(ActiveRuntime runtime, String direction, BigDecimal quantity, BigDecimal price, Instant klineOpenTime,
     String idempotencyKey) {
        QuantStrategyPositionState state = runtime.state == null ? new QuantStrategyPositionState() : runtime.state;
        state.setInstanceId(runtime.instance.getId());
        PositionProtectionState.startCycle(state, runtime.strategySymbol.getLeverage() == null ? runtime.strategy.getDefaultLeverage() : runtime.strategySymbol.getLeverage(),
            runtime.strategySymbol.getStopLossRatio(), price);
        runtime.lastProtectionTarget = null;
        state.setStatus("OPEN_PENDING");
        state.setDirection(direction);
        state.setEntryPrice(price);
        state.setPositionQty(quantity);
        state.setInitialPositionQty(quantity);
        state.setInitialMargin(price.multiply(quantity).divide(BigDecimal.valueOf(Math.max(1, runtime.strategySymbol.getLeverage() == null ?
         runtime.strategy.getDefaultLeverage() : runtime.strategySymbol.getLeverage())), 16, RoundingMode.HALF_UP));
        state.setFrozenStopLossRatio(resolveHardStopRatio(runtime));
        state.setStrategyParamsSnapshot(writeParams(runtime.params));
        state.setPendingAction("OPEN");
        state.setPendingIdempotencyKey(idempotencyKey);
        state.setPendingOrderTime(System.currentTimeMillis());
        state.setPendingNotFoundConfirmations(0);
        state.setRecoveryRequired(false);
        state.setUnsafeRecovery(false);
        stateService.save(state);
        return state;
    }

    /**
     * 在发送退出执行前持久化待确认动作。
     */
    private QuantStrategyPositionState preparePendingStateForExit(ActiveRuntime runtime, String type, BigDecimal quantity, Instant klineOpenTime, String idempotencyKey) {
        QuantStrategyPositionState state = runtime.state == null ? new QuantStrategyPositionState() : runtime.state;
        state.setInstanceId(runtime.instance.getId());
        state.setStatus("EXIT_PENDING");
        state.setPositionId(runtime.position.getId());
        state.setDirection(runtime.position.getPositionSide());
        state.setPositionQty(runtime.position.getQuantity());
        state.setPendingAction(type);
        state.setPendingIdempotencyKey(idempotencyKey);
        state.setPendingOrderTime(System.currentTimeMillis());
        state.setPendingNotFoundConfirmations(0);
        stateService.save(state);
        return state;
    }

    /**
     * 恢复 OPEN/CLOSE 的未知执行状态。订单仍在交易所活动态时绝不能把本地状态强行关闭。
     */
    private boolean reconcilePendingExecution(ActiveRuntime runtime) {
        QuantStrategyPositionState expectedState;
        List<Object> expectedPending;
        String expectedDirection;
        synchronized (runtime) {
            if (runtime.closed || !runtime.hasPendingAction()) {
                return false;
            }
            if ("PAPER".equalsIgnoreCase(runtime.credential.getRunMode())) {
                return reconcilePaperPendingExecution(runtime);
            }
            expectedState = runtime.state;
            expectedPending = pendingIdentity(expectedState);
            expectedDirection = expectedState.getDirection();
        }
        // 网络读取不持有行情锁；只有读取完成且待确认身份未变时才提交恢复结果。
        String key = (String) expectedPending.get(1);
        QuantExecutionRecord execution = key == null || key.isBlank() ? null : executionRecordService.find(runtime.credential.getId(), key);
        Long orderId = (Long) expectedPending.get(2);
        String clientOrderId = (String) expectedPending.get(3);
        if (execution != null) {
            if (orderId == null) {
                orderId = execution.getExchangeOrderId();
            }
            if (clientOrderId == null || clientOrderId.isBlank()) {
                clientOrderId = execution.getClientOrderId();
            }
        }
        var credential = executionService.toCredential(runtime.credential);
        ExchangeGateway.OrderSnapshot order = null;
        if (orderId != null || clientOrderId != null && !clientOrderId.isBlank()) {
            order = gateway.queryOrder(credential, runtime.symbol.getSymbol(), orderId, clientOrderId);
        }
        List<ExchangeGateway.PositionSnapshot> snapshots = null;
        if (order == null || "NEW".equalsIgnoreCase(order.status()) || "PARTIALLY_FILLED".equalsIgnoreCase(order.status()) || "FILLED".equalsIgnoreCase(order.status())) {
            // 订单查询之后再读取持仓，防止用成交之前的快照解除待确认保护。
            snapshots = reconciliationService.refreshSymbolPosition(runtime.credential, runtime.symbol.getSymbol(), credential);
        }
        boolean closedOpenProven = false;
        if (order != null && "FILLED".equalsIgnoreCase(order.status()) && "OPEN".equals(expectedPending.get(0)) && snapshots != null && snapshots.stream().allMatch(p -> p.quantity() != null && p.quantity().signum() == 0)) {
            Instant from = expectedPending.get(4) instanceof Long time ? Instant.ofEpochMilli(time) : order.updateTime();
            Instant to = Instant.now();
            // 成交接口窗口有限；缺少完整历史或双向持仓归属时保留 pending，不能仅凭空仓放行。
            if (from != null && !from.isAfter(to) && from.isAfter(to.minusSeconds(6 * 24 * 3600)) && !gateway.queryPositionMode(credential).hedgeMode()) {
                var trades = gateway.queryTrades(credential, runtime.symbol.getSymbol(), new ExchangeGateway.TimeRange(from, to));
                closedOpenProven = closedPendingOpenProven(order, expectedDirection, trades);
                if (closedOpenProven) {
                    // 成交证明读取之后再次确认空仓，避免使用旧的持仓快照提交恢复。
                    snapshots = reconciliationService.refreshSymbolPosition(runtime.credential, runtime.symbol.getSymbol(), credential);
                    closedOpenProven =
snapshots != null && snapshots.stream().allMatch(p -> runtime.symbol.getSymbol().equalsIgnoreCase(p.symbol()) && p.quantity() != null && p.quantity().signum() == 0);
                }
            }
        }
        if (decisionLogs != null && order != null) {
            decisionLogs.order(runtime.credential.getId(), order);
        }
        synchronized (runtime) {
            if (runtime.closed || runtime.state != expectedState || !Objects.equals(expectedPending, pendingIdentity(runtime.state))) {
                runtime.reconcileRequested.set(true);
                return true;
            }
            if (!leaseService.acquireOrRenew(runtime.instance.getId(), runtime.credential.getId(), runtime.symbol.getSymbol())) {
                return true;
            }
            return applyPendingExecution(runtime, execution, order, snapshots, closedOpenProven);
        }
    }

    private List<Object> pendingIdentity(QuantStrategyPositionState state) {
        return Arrays.asList(state.getPendingAction(), state.getPendingIdempotencyKey(), state.getPendingOrderId(), state.getPendingClientOrderId(), state.getPendingOrderTime(),
         state.getStatus(), state.getPositionQty(), state.getPendingNotFoundConfirmations());
    }

    /**
     * 完整单向开平仓成交证明；不接受缺失、截断或其他开仓订单混入的历史。
     */
    private boolean closedPendingOpenProven(ExchangeGateway.OrderSnapshot order, String direction, List<ExchangeGateway.TradeSnapshot> trades) {
        if (order.orderId() == null || order.executedQuantity() == null || order.executedQuantity().signum() <= 0 || !Set.of("LONG", "SHORT").contains(direction == null ? "" :
         direction) || trades == null || trades.isEmpty() || trades.size() >= 1000) {
            return false;
        }
        String openingSide = "LONG".equals(direction) ? "BUY" : "SELL";
        if (!openingSide.equalsIgnoreCase(order.side())) {
            return false;
        }
        Set<Long> seen = new HashSet<>();
        for (var trade : trades) {
            if (trade == null || trade.tradeId() == null || !seen.add(trade.tradeId()) || trade.orderId() == null || trade.time() == null || !order.symbol().equalsIgnoreCase(trade.symbol()) || trade.quantity() == null || trade.quantity().signum() <= 0 || !("BUY".equalsIgnoreCase(trade.side()) || "SELL".equalsIgnoreCase(trade.side()))) {
                return false;
            }
        }
        var ordered = trades.stream().sorted(Comparator.comparing(ExchangeGateway.TradeSnapshot::time).thenComparing(ExchangeGateway.TradeSnapshot::tradeId)).toList();
        BigDecimal opened = BigDecimal.ZERO;
        BigDecimal remaining = BigDecimal.ZERO;
        for (var trade : ordered) {
            if (openingSide.equalsIgnoreCase(trade.side())) {
                if (!order.orderId().equals(trade.orderId())) {
                    return false;
                }
                opened = opened.add(trade.quantity());
                remaining = remaining.add(trade.quantity());
            } else {
                if (order.orderId().equals(trade.orderId())) {
                    return false;
                }
                remaining = remaining.subtract(trade.quantity());
                if (remaining.signum() < 0) {
                    return false;
                }
            }
        }
        return opened.compareTo(order.executedQuantity()) == 0 && remaining.signum() == 0;
    }

    /**
     * 根据已确认的订单与成交推进等待中的策略动作。
     */
    private boolean applyPendingExecution(ActiveRuntime runtime, QuantExecutionRecord execution, ExchangeGateway.OrderSnapshot order,
     List<ExchangeGateway.PositionSnapshot> snapshots) {
        return applyPendingExecution(runtime, execution, order, snapshots, false);
    }

    private boolean applyPendingExecution(ActiveRuntime runtime, QuantExecutionRecord execution, ExchangeGateway.OrderSnapshot order,
                                          List<ExchangeGateway.PositionSnapshot> snapshots, boolean closedOpenProven) {
        if (!runtime.hasPendingAction() || runtime.state == null) {
            return false;
        }
        if ("PAPER".equalsIgnoreCase(runtime.credential.getRunMode())) {
            return reconcilePaperPendingExecution(runtime);
        }
        ExchangeGateway.Credential credential = executionService.toCredential(runtime.credential);
        String pendingKey = runtime.state.getPendingIdempotencyKey();
        if (pendingKey != null && !pendingKey.isBlank()) {
            if (execution != null) {
                boolean changed = false;
                if (runtime.state.getPendingOrderId() == null && execution.getExchangeOrderId() != null) {
                    runtime.state.setPendingOrderId(execution.getExchangeOrderId());
                    changed = true;
                }
                if ((runtime.state.getPendingClientOrderId() == null || runtime.state.getPendingClientOrderId().isBlank()) && execution.getClientOrderId() != null && !execution.getClientOrderId().isBlank()) {
                    runtime.state.setPendingClientOrderId(execution.getClientOrderId());
                    changed = true;
                }
                if (changed) {
                    stateService.save(runtime.state);
                }
            }
        }
        if (runtime.state.getPendingOrderId() == null && (runtime.state.getPendingClientOrderId() == null || runtime.state.getPendingClientOrderId().isBlank())) {
            runtime.position = loadPosition(runtime.credential, runtime.symbol.getSymbol(), credential, snapshots);
            if (runtime.position != null && "OPEN".equalsIgnoreCase(runtime.state.getPendingAction())) {
                // 只有持仓不是本次订单成交证明；缺少订单身份时保留 pending，不猜测初始数量或归属。
                runtime.state.setRecoveryRequired(true);
                runtime.state.setUnsafeRecovery(true);
                stateService.save(runtime.state);
                return true;
            }
            if (execution != null && "FAILED".equalsIgnoreCase(execution.getExecutionStatus())) {
                if ("OPEN".equalsIgnoreCase(runtime.state.getPendingAction())) {
                    stateService.markClosed(runtime.state);
                } else {
                    clearPending(runtime.state);
                    runtime.state.setStatus("OPEN");
                    stateService.save(runtime.state);
                }
                return true;
            }
            runtime.state.setRecoveryRequired(true);
            runtime.state.setUnsafeRecovery("OPEN".equalsIgnoreCase(runtime.state.getPendingAction()));
            stateService.save(runtime.state);
            return true;
        }
        if (order == null) {
            if (snapshots != null && !snapshots.isEmpty()) {
                // 查不到订单不代表没有敞口：刷新实际持仓，外层继续挂载硬止损，保留待确认身份。
                runtime.position = loadPosition(runtime.credential, runtime.symbol.getSymbol(), credential, snapshots);
            }
            int confirmations = runtime.state.getPendingNotFoundConfirmations() == null ? 0 : runtime.state.getPendingNotFoundConfirmations();
            confirmations++;
            runtime.state.setPendingNotFoundConfirmations(confirmations);
            if (confirmations < 3) {
                stateService.save(runtime.state);
                return true;
            }
            if ("OPEN".equalsIgnoreCase(runtime.state.getPendingAction()) && execution != null && "FAILED".equalsIgnoreCase(execution.getExecutionStatus()) && snapshots != null && snapshots.isEmpty() && !runtime.protectionSyncing.get() && !runtime.protectionCleanupPending) {
                runtime.position = null;
                stateService.markClosed(runtime.state);
                log.info("待确认开仓恢复完成：执行已明确失败且交易所空仓 instanceId={} symbol={}", runtime.instance.getId(), runtime.symbol.getSymbol());
                return true;
            }
            runtime.state.setRecoveryRequired(true);
            stateService.save(runtime.state);
            log.warn("待确认订单未找到，保留开仓保护 instanceId={} symbol={} pendingAction={} executionStatus={} positionCount={}", runtime.instance.getId(), runtime.symbol.getSymbol(),
             runtime.state.getPendingAction(), execution == null ? "MISSING" : execution.getExecutionStatus(), snapshots == null ? null : snapshots.size());
            return true;
        }
        String status = order.status();
        if ("NEW".equalsIgnoreCase(status) || "PARTIALLY_FILLED".equalsIgnoreCase(status)) {
            runtime.position = loadPosition(runtime.credential, runtime.symbol.getSymbol(), credential, snapshots);
            runtime.state.setPendingNotFoundConfirmations(0);
            stateService.save(runtime.state);
            return true;
        }
        if ("FILLED".equalsIgnoreCase(status)) {
            runtime.state.setPendingNotFoundConfirmations(0);
            runtime.position = loadPosition(runtime.credential, runtime.symbol.getSymbol(), credential, snapshots);
            if (runtime.position != null) {
                if ("OPEN".equals(runtime.state.getPendingAction())) {
                    runtime.state.setPositionId(runtime.position.getId());
                    runtime.state.setPositionQty(runtime.position.getQuantity());
                    runtime.state.setEntryPrice(runtime.position.getEntryPrice());
                    if (order.executedQuantity() == null || order.executedQuantity().signum() <= 0 || !runtime.state.getDirection().equalsIgnoreCase(runtime.position.getPositionSide()) || runtime.position.getQuantity().compareTo(order.executedQuantity()) > 0) {
                        runtime.state.setRecoveryRequired(true);
                        runtime.state.setUnsafeRecovery(true);
                        stateService.save(runtime.state);
                        return true;
                    }
                    runtime.state.setInitialPositionQty(order.executedQuantity());
                    runtime.state.setInitialMargin(runtime.position.getMargin());
                    freezeFilledProtection(runtime);
                    freezeOrderEntryPrice(runtime.state, order);
                    runtime.state.setStatus("OPEN");
                    clearPending(runtime.state);
                    stateService.save(runtime.state);
                    ensureHardStop(runtime);
                    BigDecimal current = runtime.currentPrice() != null ? runtime.currentPrice() : runtime.position.getMarkPrice();
                    if (current != null && current.signum() > 0) {
                        syncPluginProtections(runtime, buildContext(runtime, current, new ExchangeGateway.MarketEvent("RECOVERY", runtime.symbol.getSymbol(),
                         runtime.strategySymbol.getIntervalCode(), current, null, Instant.now())));
                    }
                    BigDecimal openRatio = runtime.strategySymbol.getOpenRatio() == null ? runtime.strategy.getDefaultOpenRatio() : runtime.strategySymbol.getOpenRatio();
                    int leverage = runtime.strategySymbol.getLeverage() == null ? runtime.strategy.getDefaultLeverage() : runtime.strategySymbol.getLeverage();
                    notificationService.notifyOpen(runtime.strategy, runtime.credential, runtime.symbol.getSymbol(), runtime.position.getPositionSide(),
                runtime.position.getQuantity(), runtime.position.getEntryPrice(), leverage, openRatio, "未知执行恢复后确认开仓成交");
                } else {
                    BigDecimal beforeQty = runtime.state.getPositionQty();
                    BigDecimal executedQty = order.executedQuantity();
                    if (beforeQty == null || executedQty == null || executedQty.signum() <= 0 || runtime.state.getDirection() == null || !runtime.state.getDirection().equalsIgnoreCase(runtime.position.getPositionSide()) || runtime.position.getQuantity().compareTo(beforeQty.subtract(executedQty).max(BigDecimal.ZERO)) > 0) {
                        // FILLED 是订单事实，持仓快照必须同时反映全部成交数量，才能解除退出保护。
                        runtime.state.setRecoveryRequired(true);
                        runtime.state.setUnsafeRecovery(true);
                        stateService.save(runtime.state);
                        return true;
                    }
                    String completedAction = runtime.state.getPendingAction();
                    if ("FIRST_TAKE_PROFIT".equalsIgnoreCase(completedAction)) {
                        PositionProtectionState.confirmLocalFirstProfit(runtime.state, executedQty);
                    }
                    runtime.state.setPositionQty(runtime.position.getQuantity());
                    clearPending(runtime.state);
                    runtime.state.setStatus("OPEN");
                    runtime.state.setRecoveryRequired(false);
                    runtime.state.setUnsafeRecovery(false);
                    stateService.save(runtime.state);
                    ensureHardStop(runtime);
                    BigDecimal current = runtime.currentPrice() != null ? runtime.currentPrice() : runtime.position.getMarkPrice();
                    if (current != null && current.signum() > 0) {
                        syncPluginProtections(runtime, buildContext(runtime, current, new ExchangeGateway.MarketEvent("RECOVERY", runtime.symbol.getSymbol(),
                         runtime.strategySymbol.getIntervalCode(), current, null, Instant.now())));
                    }
                }
                return true;
            }
            if ("OPEN".equals(runtime.state.getPendingAction())) {
                if (closedOpenProven && cancelStaleProtection(runtime)) {
                    blockCloseCandle(runtime, runtime.lastEvaluatedKlineOpenTime);
                    stateService.markClosed(runtime.state);
                    runtime.state = stateService.findByInstance(runtime.instance.getId());
                    log.info("待确认开仓恢复完成：开平仓成交完整且交易所空仓 instanceId={} symbol={} orderId={}", runtime.instance.getId(), runtime.symbol.getSymbol(), order.orderId());
                    return true;
                }
                runtime.state.setRecoveryRequired(true);
                runtime.state.setUnsafeRecovery(true);
                stateService.save(runtime.state);
                return true;
            }
            if (!cancelStaleProtection(runtime)) {
                return true;
            }
            blockCloseCandle(runtime, runtime.lastEvaluatedKlineOpenTime);
            stateService.markClosed(runtime.state);
            runtime.state = stateService.findByInstance(runtime.instance.getId());
            return true;
        }
        if ("CANCELED".equalsIgnoreCase(status) || "EXPIRED".equalsIgnoreCase(status) || "REJECTED".equalsIgnoreCase(status)) {
            BigDecimal executed = order.executedQuantity();
            if (executed == null) {
                runtime.state.setRecoveryRequired(true);
                runtime.state.setUnsafeRecovery(true);
                stateService.save(runtime.state);
                return true;
            }
            if (executed.signum() > 0) {
                BigDecimal beforeQty = runtime.state.getPositionQty();
                runtime.position = loadPosition(runtime.credential, runtime.symbol.getSymbol(), credential, snapshots);
                if ("OPEN".equalsIgnoreCase(runtime.state.getPendingAction())) {
                    if (runtime.position == null || !runtime.state.getDirection().equalsIgnoreCase(runtime.position.getPositionSide()) || runtime.position.getQuantity().compareTo(executed) != 0) {
                        runtime.state.setRecoveryRequired(true);
                        runtime.state.setUnsafeRecovery(true);
                        stateService.save(runtime.state);
                        return true;
                    }
                    runtime.state.setInitialPositionQty(executed);
                    runtime.state.setPositionQty(executed);
                    freezeFilledProtection(runtime);
                } else if (runtime.position != null && (beforeQty == null || runtime.position.getQuantity().compareTo(beforeQty.subtract(executed).max(BigDecimal.ZERO)) > 0)) {
                    runtime.state.setRecoveryRequired(true);
                    runtime.state.setUnsafeRecovery(true);
                    stateService.save(runtime.state);
                    return true;
                } else if ("FIRST_TAKE_PROFIT".equalsIgnoreCase(runtime.state.getPendingAction())) {
                    PositionProtectionState.confirmLocalFirstProfit(runtime.state, executed);
                }
                if (runtime.position == null && !"OPEN".equalsIgnoreCase(runtime.state.getPendingAction())) {
                    blockCloseCandle(runtime, runtime.lastEvaluatedKlineOpenTime);
                }
            }
            clearPending(runtime.state);
            if (runtime.position != null) {
                runtime.state.setStatus("OPEN");
                stateService.save(runtime.state);
            } else {
                stateService.markClosed(runtime.state);
            }
            return true;
        }
        return true;
    }

    /**
     * 恢复 PAPER 待确认执行并防止重复结算。
     */
    private boolean reconcilePaperPendingExecution(ActiveRuntime runtime) {
        String key = runtime.state.getPendingIdempotencyKey();
        if (key == null || key.isBlank()) {
            runtime.state.setRecoveryRequired(true);
            runtime.state.setUnsafeRecovery(true);
            stateService.save(runtime.state);
            return true;
        }
        try {
            var execution = executionRecordService.find(runtime.credential.getId(), key);
            String executionStatus = execution == null ? "MISSING" : execution.getExecutionStatus();
            if ("MISSING".equals(executionStatus)) {
                runtime.state.setRecoveryRequired(true);
                runtime.state.setUnsafeRecovery(true);
                stateService.save(runtime.state);
                return true;
            }
            if ("PENDING".equalsIgnoreCase(executionStatus)) {
                // PAPER 执行不存在交易所外部副作用；PENDING 说明本地执行事务尚未确认，不得凭空创建成交。
                if (runtime.state.getPendingOrderTime() != null && System.currentTimeMillis() - runtime.state.getPendingOrderTime() > 30_000L) {
                    if ("OPEN".equalsIgnoreCase(runtime.state.getPendingAction())) {
                        paperTradingService.failPendingOpen(runtime.credential.getId(), key, runtime.state.getInitialMargin(), "PAPER 执行在确认成交前中断，已按无外部副作用安全回滚");
                        clearPending(runtime.state);
                        runtime.state.setStatus("CLOSED");
                        runtime.state.setRecoveryRequired(false);
                        runtime.state.setUnsafeRecovery(false);
                    } else {
                        executionRecordService.markRecoveryAttempt(execution);
                        executionRecordService.failed(execution, "PAPER 平仓在本地结算前中断，未发生外部副作用");
                        clearPending(runtime.state);
                        runtime.state.setStatus("OPEN");
                        runtime.state.setRecoveryRequired(false);
                        runtime.state.setUnsafeRecovery(false);
                    }
                    stateService.save(runtime.state);
                }
                return true;
            }
            if ("OPEN".equalsIgnoreCase(runtime.state.getPendingAction())) {
                QuantPosition position = loadPosition(runtime.credential, runtime.symbol.getSymbol(), null);
                if (position == null) {
                    position = paperTradingService.recordOpenFill(runtime.credential.getId(), key, runtime.symbol.getSymbol(), runtime.state.getDirection(),
                     runtime.state.getPositionQty(), runtime.state.getEntryPrice(), runtime.strategySymbol.getLeverage() == null ? runtime.strategy.getDefaultLeverage() :
                      runtime.strategySymbol.getLeverage());
                }
                runtime.position = position;
                runtime.state.setPositionId(position.getId());
                runtime.state.setPositionQty(position.getQuantity());
                runtime.state.setStatus("OPEN");
                clearPending(runtime.state);
                runtime.state.setRecoveryRequired(false);
                runtime.state.setUnsafeRecovery(false);
                stateService.save(runtime.state);
                return true;
            }
            if (runtime.position == null) {
                runtime.position = loadPosition(runtime.credential, runtime.symbol.getSymbol(), null);
            }
            if (runtime.position == null) {
                runtime.state.setRecoveryRequired(true);
                runtime.state.setUnsafeRecovery(true);
                stateService.save(runtime.state);
                return true;
            }
            BigDecimal closeQty = runtime.state.getPositionQty() == null ? runtime.position.getQuantity() : runtime.state.getPositionQty();
            BigDecimal price = runtime.position.getMarkPrice() == null ? runtime.position.getEntryPrice() : runtime.position.getMarkPrice();
            PaperTradingService.PaperCloseSettlement settlement = paperTradingService.settlePaperExecution(runtime.credential.getId(), key, runtime.position.getId(),
             closeQty.min(runtime.position.getQuantity()), price);
            runtime.position = settlement.position();
            if (runtime.position == null) {
                blockCloseCandle(runtime, runtime.lastEvaluatedKlineOpenTime);
                if (!cancelStaleProtection(runtime)) {
                    return true;
                }
                stateService.markClosed(runtime.state);
                runtime.state = stateService.findByInstance(runtime.instance.getId());
            } else {
                runtime.state.setPositionQty(runtime.position.getQuantity());
                runtime.state.setStatus("OPEN");
                runtime.state.setRecoveryRequired(false);
                runtime.state.setUnsafeRecovery(false);
                clearPending(runtime.state);
                stateService.save(runtime.state);
            }
            return true;
        } catch (RuntimeException ex) {
            runtime.state.setRecoveryRequired(true);
            runtime.state.setUnsafeRecovery(true);
            stateService.save(runtime.state);
            return true;
        }
    }

    /**
     * 查询指定幂等键的执行状态。
     */
    private QuantExecutionRecordStatus findExecutionStatus(Long credentialId, String idempotencyKey) {
        var record = executionRecordService.find(credentialId, idempotencyKey);
        return new QuantExecutionRecordStatus(record == null ? "MISSING" : record.getExecutionStatus());
    }

    /**
     * 释放 PAPER 中已预留但未形成有效持仓的保证金。
     */
    private void releasePaperMargin(Long credentialId, BigDecimal margin) {
        if (margin == null || margin.signum() <= 0) {
            return;
        }
        paperTradingService.refundReservedMargin(credentialId, margin);
    }

    /**
     * 读取当前凭证和交易对的本地持仓事实。
     */
    private QuantPosition loadPosition(QuantCredential credential, String symbol, ExchangeGateway.Credential exchangeCredential) {
        if ("PAPER".equalsIgnoreCase(credential.getRunMode())) {
            return loadPosition(credential, symbol, exchangeCredential, null);
        }
        List<ExchangeGateway.PositionSnapshot> snapshots = reconciliationService.refreshSymbolPosition(credential, symbol, exchangeCredential);
        return loadPosition(credential, symbol, exchangeCredential, snapshots);
    }

    /**
     * 后台对账提交围栏保护的交易对快照，并读取提交后的持仓事实。
     */
    private QuantPosition loadReconciledPosition(ActiveRuntime runtime, List<QuantPosition> baseline, List<ExchangeGateway.PositionSnapshot> snapshots) {
        if ("PAPER".equalsIgnoreCase(runtime.credential.getRunMode())) {
            List<QuantPosition> open = baseline.stream().filter(p -> p.getQuantity() != null && p.getQuantity().signum() > 0).toList();
            if (open.size() > 1) {
                throw new IllegalStateException("PAPER账户存在多个非零方向持仓，拒绝接管");
            }
            return open.isEmpty() ? null : open.get(0);
        }
        List<QuantPosition> open =
        positionMapper.selectList(new LambdaQueryWrapper<QuantPosition>().eq(QuantPosition::getCredentialId, runtime.credential.getId()).eq(QuantPosition::getSymbol,
          runtime.symbol.getSymbol()).gt(QuantPosition::getQuantity, BigDecimal.ZERO));
        if (open.size() > 1) {
            throw new IllegalStateException("同一凭证同一交易对存在多个非零持仓方向，拒绝接管");
        }
        return open.isEmpty() ? null : open.get(0);
    }

    private QuantPosition loadPosition(QuantCredential credential, String symbol, ExchangeGateway.Credential exchangeCredential, List<ExchangeGateway.PositionSnapshot> snapshots) {
        if ("PAPER".equalsIgnoreCase(credential.getRunMode())) {
            List<QuantPosition> positions =
            positionMapper.selectList(new LambdaQueryWrapper<QuantPosition>().eq(QuantPosition::getCredentialId, credential.getId()).eq(QuantPosition::getSymbol, symbol).gt(QuantPosition::getQuantity, BigDecimal.ZERO));
            if (positions.size() > 1) {
                throw new IllegalStateException("PAPER 账户同一交易对存在多个非零方向持仓，拒绝自动接管: " + symbol);
            }
            return positions.isEmpty() ? null : positions.get(0);
        }
        if (snapshots == null) {
            throw new IllegalStateException("Binance symbol position snapshot missing");
        }
        List<QuantPosition> localOpen =
positionMapper.selectList(new LambdaQueryWrapper<QuantPosition>().eq(QuantPosition::getCredentialId, credential.getId()).eq(QuantPosition::getSymbol, symbol).gt(QuantPosition::getQuantity, BigDecimal.ZERO));
        if (localOpen.size() > 1) {
            throw new IllegalStateException("同一凭证同一交易对存在多个非零持仓方向，当前运行时模型拒绝自动接管: " + symbol);
        }
        return localOpen.isEmpty() ? null : localOpen.get(0);
    }

    /**
     * 创建或更新 PAPER 本地持仓。
     */
    private QuantPosition upsertPaperPosition(ActiveRuntime runtime, String direction, BigDecimal quantity, BigDecimal entryPrice, int leverage) {
        QuantPosition position = new QuantPosition();
        position.setCredentialId(runtime.credential.getId());
        position.setSymbol(runtime.symbol.getSymbol());
        position.setPositionSide(direction);
        position.setQuantity(quantity);
        position.setEntryPrice(entryPrice);
        position.setMarkPrice(entryPrice);
        position.setMargin(entryPrice.multiply(quantity).divide(BigDecimal.valueOf(Math.max(1, leverage)), 16, RoundingMode.HALF_UP));
        position.setLeverage(leverage);
        position.setUnrealizedPnl(BigDecimal.ZERO);
        position.setUpdatedAt(LocalDateTime.now());
        positionMapper.insert(position);
        return position;
    }

    /**
     * 用当前持仓事实校准策略运行状态。
     */
    private void syncRuntimeStateWithPosition(ActiveRuntime runtime) {
        // 首止盈的交易所查询由后台对账完成；此入口只提交已有事实。
        syncRuntimeStateWithPosition(runtime, false);
    }

    /**
     * 将运行时策略状态与已核验的账户持仓事实对齐。
     */
    private void syncRuntimeStateWithPosition(ActiveRuntime runtime, boolean firstTpFilled) {
        if (runtime.position == null) {
            return;
        }
        if (runtime.state == null) {
            runtime.state = recoverState(runtime);
        }
        if (runtime.state.getPositionId() == null || !runtime.position.getId().equals(runtime.state.getPositionId())) {
            runtime.state.setPositionId(runtime.position.getId());
        }
        runtime.state.setPositionQty(runtime.position.getQuantity());
        if (firstTpFilled) {
            runtime.state.setFirstTakeProfitTriggered(true);
        }
        runtime.state.setStatus("OPEN");
        clearPending(runtime.state);
        stateService.save(runtime.state);
    }

    /**
     * 根据持仓事实恢复策略运行状态。
     */
    private QuantStrategyPositionState recoverState(ActiveRuntime runtime) {
        QuantStrategyPositionState state = new QuantStrategyPositionState();
        state.setInstanceId(runtime.instance.getId());
        state.setPositionId(runtime.position.getId());
        state.setStatus("OPEN");
        state.setDirection(runtime.position.getPositionSide());
        state.setEntryPrice(runtime.position.getEntryPrice());
        state.setPositionQty(runtime.position.getQuantity());
        state.setInitialPositionQty(runtime.position.getQuantity());
        state.setInitialMargin(runtime.position.getMargin());
        state.setFrozenStopLossRatio(resolveHardStopRatio(runtime));
        state.setStrategyParamsSnapshot(writeParams(runtime.params));
        state.setRecoveryRequired(true);
        state.setUnsafeRecovery(true);
        state.setOpenedAt(null);
        log.error("历史持仓缺少冻结参数和首次止盈事实，保留已有保护并暂停自动接管 instanceId={} symbol={}", runtime.instance.getId(), runtime.symbol.getSymbol());
        stateService.save(state);
        return stateService.findByInstance(runtime.instance.getId());
    }

    /**
     * 确保当前持仓存在正确数量和触发价的固定硬止损。
     */
    private void ensureHardStop(ActiveRuntime runtime) {
        requestProtectionSync(runtime, false);
    }

    /**
     * 只在短锁内捕获持仓快照；所有交易所保护 REST 在锁外运行。
     */
    private void requestProtectionSync(ActiveRuntime runtime, boolean dynamic) {
        synchronized (runtime) {
            if (runtime.closed || runtime.position == null || runtime.exitCoordinating.get() || !hasFrozenProtectionFacts(runtime)) {
                return;
            }
            runtime.protectionRequested.set(true);
            // 首次同步必须携带 dynamic，确保插件信号（含 localStopLossRate 镜像）与硬止损一次挂上
            if (dynamic || runtime.lastProtectionTarget == null) {
                runtime.dynamicProtectionRequested.set(true);
            }
            if (!runtime.protectionSyncing.compareAndSet(false, true)) {
                return;
            }
        }
        synchronized (runtime) {
            if (runtime.closed) {
                runtime.protectionSyncing.set(false);
                return;
            }
            runtime.protectionTask = recoveryExecutor.submit(() -> {
                Thread heartbeat = null;
                AtomicBoolean renewing = new AtomicBoolean(true);
                AtomicBoolean leaseHeld = new AtomicBoolean(true);
                try {
                    do {
                        runtime.protectionRequested.set(false);
                        ProtectionWork work;
                        synchronized (runtime) {
                            if (runtime.closed || runtime.position == null) {
                                break;
                            }
                            QuantPosition snapshot = new QuantPosition();
                            BeanUtils.copyProperties(runtime.position, snapshot);
                            snapshot.setProtectionCycleKey(runtime.state == null ? null : runtime.state.getPositionCycleKey());
                            if (runtime.state != null && runtime.state.getFrozenLeverage() != null) {
                                snapshot.setLeverage(runtime.state.getFrozenLeverage());
                                snapshot.setEntryPrice(runtime.state.getEntryPrice());
                            }
                            boolean includeDynamic = runtime.dynamicProtectionRequested.getAndSet(false) && !runtime.hasPendingAction();
                            BigDecimal hardStopRatio = resolveHardStopRatio(runtime);
                            BigDecimal current = runtime.currentPrice() != null ? runtime.currentPrice() : snapshot.getMarkPrice();
                            List<ProtectionSignal> signals = List.of();
                            RuntimeException signalFailure = null;
                            try {
                                if (includeDynamic) {
                                    if (current == null || current.signum() <= 0) {
                                        throw new IllegalStateException("缺少有效行情价格，不能确认策略止盈止损目标");
                                    }
                                    signals = protectionSignals(runtime, buildContext(runtime, current, new ExchangeGateway.MarketEvent("PROTECTION_SYNC",
                                     runtime.symbol.getSymbol(), runtime.strategySymbol.getIntervalCode(), current, null, Instant.now())), frozenParams(runtime));
                                }
                            } catch (RuntimeException ex) {
                                // 目标计算失败不能阻断硬止损，也不能以空目标撤掉已有动态保护。
                                signalFailure = ex;
                            }
                            work = new ProtectionWork(runtime.credential.getId(), snapshot, runtime.state, hardStopRatio, runtime.state == null ? snapshot.getQuantity() :
                             runtime.state.getInitialPositionQty(), signals, includeDynamic, !"PAPER".equalsIgnoreCase(runtime.credential.getRunMode()), signalFailure);
                        }
                        if (runtime.closed || !leaseHeld.get()) {
                            break;
                        }
                        if (!leaseService.acquireOrRenew(runtime.instance.getId(), work.credentialId(), work.position().getSymbol())) {
                            runtime.marketHealthy = false;
                            runtime.protectionRequested.set(false);
                            break;
                        }
                        if (heartbeat == null) {
                            heartbeat = Thread.ofVirtual().name("quant-protection-lease").start(() -> {
                                try {
                                    while (renewing.get()) {
                                        Thread.sleep(5000);
                                        if (!renewing.get()) {
                                            break;
                                        }
                                        if (!leaseService.acquireOrRenew(runtime.instance.getId(), runtime.credential.getId(), runtime.symbol.getSymbol())) {
                                            leaseHeld.set(false);
                                            runtime.marketHealthy = false;
                                            break;
                                        }
                                    }
                                } catch (InterruptedException ex) {
                                    Thread.currentThread().interrupt();
                                } catch (RuntimeException ex) {
                                    leaseHeld.set(false);
                                    runtime.marketHealthy = false;
                                }
                            });
                        }
                        RuntimeException failure = null;
                        boolean hardStopConfirmed = false;
                        int confirmedStep = 0;
                        // 硬止损优先并独立确认；止盈等其他目标失败不能跳过硬止损。
                        try {
                            if (work.ratio() == null || work.ratio().signum() <= 0) {
                                throw new IllegalStateException("缺少有效硬止损比例");
                            }
                            if (protectionOrderService.ensureMarginHardStop(work.credentialId(), work.position(), work.ratio(), work.live()) == null) {
                                throw new IllegalStateException("持仓字段不足，硬止损未创建");
                            }
                            hardStopConfirmed = true;
                        } catch (RuntimeException ex) {
                            failure = ex;
                        }
                        try {
                            if (work.signalFailure() != null) {
                                throw work.signalFailure();
                            }
                            if (work.dynamic() && !runtime.closed && leaseHeld.get() && leaseService.acquireOrRenew(runtime.instance.getId(), work.credentialId(),
                             work.position().getSymbol())) {
                                List<ProtectionSignal> exchangeSignals = deduplicateExchangeProtectionSignals(work.signals(), work.position(), work.initialQuantity(),
                                 hardStopConfirmed ? work.ratio() : null, runtime.protectionRule, work.state() != null && work.state().getFrozenLeverage() != null ?
                                  work.state().getFrozenLeverage() : work.position().getLeverage() == null ? 1 : work.position().getLeverage());
                                protectionOrderService.syncStrategyProtections(work.credentialId(), work.position(), work.initialQuantity(), exchangeSignals,
                            work.state() == null ? null : work.state().getFirstTakeProfitTargetQty() == null ? null :
                                  PositionProtectionState.remainingFirstProfit(work.state()), work.live());
                            }
                            if (failure == null) {
                                confirmedStep = protectionOrderService.confirmedStep(work.credentialId(), work.position());
                            }
                        } catch (RuntimeException ex) {
                            if (failure == null) {
                                failure = ex;
                            } else if (failure != ex) {
                                failure.addSuppressed(ex);
                            }
                        }
                        if (!leaseHeld.get() || !leaseService.acquireOrRenew(runtime.instance.getId(), work.credentialId(), work.position().getSymbol())) {
                            runtime.marketHealthy = false;
                            runtime.protectionRequested.set(false);
                            break;
                        }
                        boolean obsolete;
                        synchronized (runtime) {
                            obsolete =
                             runtime.position == null || !Objects.equals(runtime.position.getId(), work.position().getId()) || runtime.state == null || !Objects.equals(runtime.state.getPositionCycleKey(), work.position().getProtectionCycleKey());
                            boolean current =
                             !runtime.closed && !obsolete && runtime.state == work.state() && runtime.position.getQuantity().compareTo(work.position().getQuantity()) == 0;
                            if (current) {
                                runtime.hardStopConfirmed = hardStopConfirmed;
                                if (failure != null) {
                                    markProtectionDegraded(runtime, "交易所保护创建/同步失败", failure);
                                } else {
                                    int previousStep = runtime.state.getConfirmedProtectionStep() == null ? 0 : runtime.state.getConfirmedProtectionStep();
                                    int highestStep = runtime.state.getHighestStep() == null ? 0 : runtime.state.getHighestStep();
                                    if (confirmedStep > previousStep || confirmedStep > highestStep) {
                                        runtime.state.setConfirmedProtectionStep(Math.max(previousStep, confirmedStep));
                                        runtime.state.setHighestStep(Math.max(highestStep, confirmedStep));
                                        stateService.save(runtime.state);
                                    }
                                    if (work.dynamic() && runtime.protectionDegraded && !runtime.hasPendingAction()) {
                                        runtime.state.setRecoveryRequired(false);
                                        runtime.state.setUnsafeRecovery(false);
                                        stateService.save(runtime.state);
                                        runtime.protectionDegraded = false;
                                    }
                                }
                            } else if (!runtime.closed && !obsolete) {
                                // 同一持仓数量改变：下一轮先挂正确保护再撤旧，不能清空全部保护。
                                runtime.protectionRequested.set(true);
                            }
                        }
                        if (obsolete) {
                            QuantStrategyPositionState cleanupState;
                            synchronized (runtime) {
                                runtime.protectionCleanupPending = true;
                                cleanupState = runtime.state;
                                if (runtime.position == null && cleanupState != null) {
                                    // 旧保护任务已过期，先阻止开仓并标记待清理；此时不能仅凭本地 position=null 判定交易所空仓。
                                    cleanupState.setStatus("PROTECTION_CLEANUP");
                                    cleanupState.setRecoveryRequired(true);
                                    cleanupState.setUnsafeRecovery(true);
                                    stateService.save(cleanupState);
                                }
                            }
                            // 先确认交易所确实空仓，再撤保护单；本地持仓过期不能作为撤单依据。
                            ProtectionCleanupResult cleanup = cleanupProtectionIfExchangeFlat(runtime, work.position().getId(), work.position().getProtectionCycleKey(),
                             work.live(), "迟到保护清理");
                            boolean exchangeFlat = cleanup.exchangeFlat();
                            boolean cleaned = cleanup.cleaned();

                            synchronized (runtime) {
                                runtime.protectionCleanupPending = !cleaned;
                                // 防止异步期间状态/持仓被另一条执行路径更新：仅处理同一状态对象且仍确认本地无持仓的情况。
                                if (exchangeFlat && !runtime.closed && runtime.position == null && runtime.state == cleanupState && cleanupState != null && !runtime.hasPendingAction()) {
                                    blockCloseCandle(runtime, runtime.lastEvaluatedKlineOpenTime);
                                    stateService.markClosed(cleanupState);
                                    runtime.state = stateService.findByInstance(runtime.instance.getId());
                                    // 保护单撤销失败仍必须阻止新开仓；但已确认空仓的记录不应继续占用最大持仓数。
                                    runtime.protectionCleanupPending = !cleaned;
                                    log.info("迟到保护清理后已确认交易所空仓，释放策略持仓名额 instanceId={} symbol={} protectionCleaned={}", runtime.instance.getId(), runtime.symbol.getSymbol(),
                                     cleaned);
                                }
                            }
                            if (!exchangeFlat) {
                                log.error("交易所持仓未确认空仓，保留保护单并禁止下一次开仓 instanceId={} positionId={}", runtime.instance.getId(), work.position().getId());
                            } else if (!cleaned) {
                                log.error("迟到保护未清理，禁止下一次开仓 instanceId={} positionId={}", runtime.instance.getId(), work.position().getId());
                            }
                            requestReconciliation(runtime);
                        }
                    } while (!runtime.closed && !runtime.exitCoordinating.get() && runtime.protectionRequested.get());
                } catch (RuntimeException ex) {
                    synchronized (runtime) {
                        if (!runtime.closed) {
                            markProtectionDegraded(runtime, "异步保护恢复失败", ex);
                        }
                    }
                } finally {
                    renewing.set(false);
                    if (heartbeat != null) {
                        heartbeat.interrupt();
                        boolean interrupted = false;
                        for (; ; ) {
                            try {
                                heartbeat.join();
                                break;
                            } catch (InterruptedException ex) {
                                interrupted = true;
                            }
                        }
                        if (interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    runtime.protectionSyncing.set(false);
                    if (!runtime.closed && runtime.protectionRequested.get()) {
                        requestProtectionSync(runtime, runtime.dynamicProtectionRequested.get());
                    }
                }
            });
        }
    }

    /**
     * 标记保护链路降级并阻止后续开仓，等待保护状态恢复确认。
     */
    private void markProtectionDegraded(ActiveRuntime runtime, String reason, RuntimeException ex) {
        if (runtime.state != null) {
            runtime.state.setRecoveryRequired(true);
            runtime.state.setUnsafeRecovery(true);
            stateService.save(runtime.state);
        }
        // 保留运行时监管和本地退出能力，仅阻止下一次开仓。
        runtime.protectionDegraded = true;
        runtime.instance.setLastError(reason + "，保留本地止损并等待恢复: " + ex.getMessage());
        instanceMapper.updateById(runtime.instance);
        log.error("{} instanceId={} symbol={}", reason, runtime.instance.getId(), runtime.symbol.getSymbol(), ex);
    }

    /**
     * 对已确认空仓但保护单仍未清理完成的状态进行重试。
     * <p>该重试不依赖 position 状态仍为 OPEN，避免状态归零后失去后续清理机会。
     */
    private void retryProtectionCleanupIfNeeded(ActiveRuntime runtime) {
        final QuantStrategyPositionState expectedState;
        final Long positionId;
        synchronized (runtime) {
            if (runtime.closed || !runtime.protectionCleanupPending || runtime.position != null || runtime.state == null || runtime.state.getPositionId() == null || runtime.hasPendingAction()) {
                return;
            }
            expectedState = runtime.state;
            positionId = expectedState.getPositionId();
        }
        boolean cleaned;
        try {
            boolean live = !"PAPER".equalsIgnoreCase(runtime.credential.getRunMode());
            ProtectionCleanupResult cleanup = cleanupProtectionIfExchangeFlat(runtime, positionId, expectedState.getPositionCycleKey(), live, "残留保护单重试");
            if (!cleanup.exchangeFlat()) {
                requestReconciliation(runtime);
                return;
            }
            cleaned = cleanup.cleaned();
        } catch (RuntimeException ex) {
            log.warn("残留保护单重试清理失败，继续阻止开仓 instanceId={} positionId={} error={}", runtime.instance.getId(), positionId, ex.getMessage());
            return;
        }
        synchronized (runtime) {
            if (runtime.state == expectedState && runtime.position == null && !runtime.closed) {
                runtime.protectionCleanupPending = !cleaned;
                if (cleaned) {
                    runtime.state.setRecoveryRequired(false);
                    runtime.state.setUnsafeRecovery(false);
                    stateService.save(runtime.state);
                    log.info("残留保护单重试清理成功，解除开仓阻断 instanceId={} positionId={}", runtime.instance.getId(), positionId);
                }
            }
        }
    }

    /**
     * 只有交易所持仓确认归零后才清理该周期保护记录。
     */
    private ProtectionCleanupResult cleanupProtectionIfExchangeFlat(ActiveRuntime runtime, Long positionId, String cycleKey, boolean live, String reason) {
        List<ExchangeGateway.PositionSnapshot> snapshots;
        try {
            snapshots = gateway.queryPositions(executionService.toCredential(runtime.credential), runtime.symbol.getSymbol());
        } catch (RuntimeException ex) {
            log.warn("{}时无法确认交易所持仓，保留所有保护单 instanceId={} positionId={} symbol={} error={}", reason, runtime.instance.getId(), positionId, runtime.symbol.getSymbol(),
             ex.getClass().getSimpleName());
            return new ProtectionCleanupResult(false, false);
        }
        boolean exchangeFlat = snapshots != null && snapshots.stream().allMatch(snapshot -> snapshot.quantity() != null && snapshot.quantity().signum() == 0);
        if (!exchangeFlat) {
            log.warn("{}时交易所仍有持仓或持仓结果不完整，保留所有保护单 instanceId={} positionId={} symbol={}", reason, runtime.instance.getId(), positionId, runtime.symbol.getSymbol());
            return new ProtectionCleanupResult(false, false);
        }
        boolean cleaned = protectionOrderService.cancelActiveProtectionOrders(runtime.credential.getId(), positionId, cycleKey, live);
        return new ProtectionCleanupResult(true, cleaned);
    }

    /**
     * 撤销与当前持仓状态不再匹配的过期保护单。
     */
    private boolean cancelStaleProtection(ActiveRuntime runtime) {
        if (runtime.state == null || runtime.state.getPositionId() == null) {
            return true;
        }
        Long positionId = runtime.state.getPositionId();
        boolean live = !"PAPER".equalsIgnoreCase(runtime.credential.getRunMode());
        boolean success = protectionOrderService.cancelActiveProtectionOrders(runtime.credential.getId(), positionId, live);

        // FIXED: 交易所已空仓时 state 必须归零；
        // "保护单未撤掉" 单独作为阻断开仓的标志，不阻塞主事实推进。
        runtime.protectionCleanupPending = !success;
        runtime.state.setStatus("CLOSED");
        runtime.state.setPositionQty(BigDecimal.ZERO);
        runtime.state.setPendingAction(null);
        runtime.state.setPendingClientOrderId(null);
        runtime.state.setPendingOrderId(null);
        runtime.state.setPendingOrderTime(null);
        runtime.state.setPendingIdempotencyKey(null);
        runtime.state.setRecoveryRequired(true);           // 保留：仍需继续尝试清理保护单
        runtime.state.setUnsafeRecovery(!success);         // 只有撤销失败时才 unsafe
        stateService.save(runtime.state);
        if (!success) {
            log.error("持仓保护单撤销失败，state 已归零但阻塞新开仓 instanceId={} positionId={}", runtime.instance.getId(), positionId);
            return false;
        }
        log.info("持仓保护单已全部撤销 instanceId={} positionId={} live={}", runtime.instance.getId(), positionId, live);
        return true;
    }

    /**
     * 加载 Runtime 启动所需的初始 K 线和价格快照。
     */
    private MarketRuntime loadInitialMarket(String runMode, String symbol, String interval, Map<String, Object> params, boolean requireSlopeReference) {
        int limit = Math.max(KlineSlopeRateStrategy.SLOPE_LOOKBACK_BARS + 1, positiveInt(params.get("historyLimit"), 50));
        List<ExchangeGateway.Kline> history = "TESTNET".equalsIgnoreCase(runMode) ? gateway.queryKlines(runMode, symbol, interval, limit) : gateway.queryKlines(symbol, interval,
            limit);
        if (requireSlopeReference && (history == null || history.size() < KlineSlopeRateStrategy.SLOPE_LOOKBACK_BARS + 1)) {
            throw new IllegalStateException("策略参考K线不足，等待历史预热: symbol=" + symbol + " interval=" + interval);
        }
        history = history.stream().sorted(java.util.Comparator.comparing(ExchangeGateway.Kline::openTime)).toList();
        MarketRuntime runtime = new MarketRuntime();
        ExchangeGateway.Kline previousClosed = null;
        for (ExchangeGateway.Kline kline : history) {
            runtime.updateKline(kline);
            if (kline.closed()) {
                previousClosed = kline;
            }
        }
        runtime.previousClosedClose = previousClosed == null ? null : previousClosed.close();
        if (requireSlopeReference) {
            BigDecimal reference = runtime.previousKlineOpenPrice();
            if (reference == null || reference.signum() <= 0) {
                throw new IllegalStateException("策略参考K线价格无效，等待历史预热: symbol=" + symbol);
            }
            for (int i = history.size() - KlineSlopeRateStrategy.SLOPE_LOOKBACK_BARS; i < history.size(); i++) {
                if (!history.get(i - 1).closeTime().plusMillis(1).equals(history.get(i).openTime())) {
                    throw new IllegalStateException("策略参考K线不连续，等待历史补偿: symbol=" + symbol);
                }
            }
        }
        return runtime;
    }

    /**
     * 更新开仓后的最高/最低价格。
     */
    private void updateRuntimeExtrema(ActiveRuntime runtime, BigDecimal price) {
        if (runtime.state == null || runtime.position == null || price == null || price.signum() <= 0) {
            return;
        }
        boolean changed = false;
        if ("SHORT".equalsIgnoreCase(runtime.position.getPositionSide())) {
            if (runtime.state.getLowestPriceSinceEntry() == null || price.compareTo(runtime.state.getLowestPriceSinceEntry()) < 0) {
                runtime.state.setLowestPriceSinceEntry(price);
                changed = true;
            }
        } else if (runtime.state.getHighestPriceSinceEntry() == null || price.compareTo(runtime.state.getHighestPriceSinceEntry()) > 0) {
            runtime.state.setHighestPriceSinceEntry(price);
            changed = true;
        }
        if (runtime.plugin instanceof KlineSlopeRateStrategy && hasFrozenProtectionFacts(runtime)) {
            int reached = KlineSlopeRateStrategy.reachedStep(buildContext(runtime, price, new ExchangeGateway.MarketEvent("EXTREMA", runtime.symbol.getSymbol(),
             runtime.strategySymbol.getIntervalCode(), price, null, Instant.now())), frozenParams(runtime));
            if (runtime.state.getHighestStep() == null || reached > runtime.state.getHighestStep()) {
                runtime.state.setHighestStep(reached);
                changed = true;
            }
        }
        if (changed) {
            runtime.extremaPersistencePending = true;
        }
        if (runtime.extremaPersistencePending) {
            stateService.save(runtime.state);
            runtime.extremaPersistencePending = false;
        }
    }

    /**
     * 同步 StrategyPlugin 计算出的动态保护单。
     */
    private void syncPluginProtections(ActiveRuntime runtime, StrategyContext context) {
        if (runtime.position == null || runtime.state == null || runtime.hasPendingAction()) {
            return;
        }
        List<ProtectionSignal> signals = protectionSignals(runtime, context, frozenParams(runtime));
        String target =
         runtime.position.getId() + ":" + runtime.position.getPositionSide() + ":" + runtime.position.getQuantity().stripTrailingZeros().toPlainString() + ":" + signals.stream().map(signal -> signal.type() + ":" + signal.orderType() + ":" + signal.quantityBase() + ":" + (signal.quantityRate() == null ? "1" : signal.quantityRate().stripTrailingZeros().toPlainString()) + ":" + (signal.triggerPrice() == null ? "null" : signal.triggerPrice().stripTrailingZeros().toPlainString())).sorted().collect(java.util.stream.Collectors.joining("|"));
        long now = System.nanoTime();
        // 相同目标合并；定期仍交给后台精确查询，变化的阶梯/数量立即同步。
        if (target.equals(runtime.lastProtectionTarget) && !runtime.protectionDegraded && now - runtime.lastProtectionRequestNanos < java.util.concurrent.TimeUnit.SECONDS.toNanos(15)) {
            return;
        }
        runtime.lastProtectionTarget = target;
        runtime.lastProtectionRequestNanos = now;
        requestProtectionSync(runtime, true);
    }

    /**
     * 解析当前持仓应使用的固定硬止损比例。
     */
    private BigDecimal resolveHardStopRatio(ActiveRuntime runtime) {
        // 1. 已冻结的历史止损比例优先（开仓时快照，中途不得改变）
        if (runtime.position != null && runtime.state != null && runtime.state.getFrozenStopLossRatio() != null && runtime.state.getFrozenStopLossRatio().signum() > 0) {
            return runtime.state.getFrozenStopLossRatio();
        }
        // 3. 策略默认止损比例
        if (runtime.strategy.getDefaultStopLossRatio() != null && runtime.strategy.getDefaultStopLossRatio().signum() > 0) {
            return runtime.strategy.getDefaultStopLossRatio();
        }
        // 4. 策略固定硬止损
        if (runtime.strategy.getStopLossRatio() != null && runtime.strategy.getStopLossRatio().signum() > 0) {
            return runtime.strategy.getStopLossRatio();
        }
        // 5. 交易对级保证金亏损比例
        if (runtime.strategySymbol.getHardStopMarginLossRatio() != null && runtime.strategySymbol.getHardStopMarginLossRatio().signum() > 0) {
            return runtime.strategySymbol.getHardStopMarginLossRatio();
        }
        // 6. 策略级保证金亏损比例
        return runtime.strategy.getHardStopMarginLossRatio();
    }

    /**
     * 合并策略默认参数与交易对级参数覆盖。
     */
    private Map<String, Object> mergedParams(QuantStrategy strategy, QuantStrategySymbol symbol) {
        Map<String, Object> result = new HashMap<>(parseParams(strategy.getParamsJson()));
        result.putAll(parseParams(symbol.getParamsJson()));
        return result;
    }

    /**
     * 解析对应业务数据。
     */
    private Map<String, Object> parseParams(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return QuantJsonMapper.readMap(json);
        } catch (Exception ex) {
            throw new IllegalArgumentException("策略参数 JSON 无效", ex);
        }
    }

    /**
     * 序列化并保存策略参数 JSON。
     */
    private String writeParams(Map<String, Object> params) {
        try {
            return QuantJsonMapper.writeMap(params);
        } catch (Exception ex) {
            throw new IllegalArgumentException("策略参数快照序列化失败", ex);
        }
    }

    /**
     * 记录策略实例最近一次运行错误。
     */
    private void recordError(QuantStrategyInstance instance, RuntimeException ex) {
        String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        instance.setLastError(message);
        instanceMapper.updateById(instance);
        if (ex instanceof org.dromara.quant.exchange.ExchangeMarketPort.RequestDeferredException) {
            log.warn("策略对账因本地REST拥堵延后 instanceId={}: {}", instance.getId(), message);
        } else {
            log.error("策略运行时执行失败 instanceId={}: {}", instance.getId(), message, ex);
        }
    }

    /**
     * 停止当前节点的全部策略 Runtime。
     */
    @PreDestroy
    public void destroy() {
        stopAll();
        recoveryExecutor.shutdownNow();
    }

    /**
     * 关闭运行实例并释放订阅、租约和调度资源。
     */
    private void stopAll() {
        activeRuntimes.values().forEach(this::closeRuntime);
        activeRuntimes.clear();
    }

    /**
     * 释放单个 Runtime 的订阅和租约资源。
     */
    private void closeRuntime(ActiveRuntime runtime) {
        if (runtime == null) {
            return;
        }
        preparationService.release(runtime.credential.getId(), runtime.symbol.getSymbol());
        java.util.concurrent.Future<?> protectionTask;
        java.util.concurrent.Future<?> exitTask;
        synchronized (runtime) {
            runtime.closed = true;
            protectionTask = runtime.protectionTask;
            exitTask = runtime.exitTask;
            if (runtime.subscription != null) {
                try {
                    runtime.subscription.close();
                } catch (Exception ignored) {
                }
                runtime.subscription = null;
            }
            if (runtime.userSubscription != null) {
                try {
                    runtime.userSubscription.close();
                } catch (Exception ignored) {
                }
                runtime.userSubscription = null;
            }
        }
        // 等待在途保护退出，但不持行情锁；在此之前继续续租，禁止新节点提前接管。
        boolean interrupted = false;
        if (protectionTask != null) {
            for (; ; ) {
                try {
                    protectionTask.get();
                    break;
                } catch (InterruptedException ex) {
                    interrupted = true;
                } catch (java.util.concurrent.ExecutionException ex) {
                    break;
                } catch (java.util.concurrent.CancellationException ex) {
                    log.error("保护任务被提前取消，暂不释放租约 instanceId={}", runtime.instance.getId());
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return;
                }
            }
        }
        if (exitTask != null) {
            for (; ; ) {
                try {
                    exitTask.get();
                    break;
                } catch (InterruptedException ex) {
                    interrupted = true;
                } catch (java.util.concurrent.ExecutionException ex) {
                    break;
                } catch (java.util.concurrent.CancellationException ex) {
                    return;
                }
            }
        }
        leaseService.release(runtime.instance.getId(), runtime.credential.getId(), runtime.symbol.getSymbol());
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private record ProtectionWork(Long credentialId, QuantPosition position, QuantStrategyPositionState state, BigDecimal ratio, BigDecimal initialQuantity,
                                  List<ProtectionSignal> signals, boolean dynamic, boolean live, RuntimeException signalFailure) {
    }

    private record ProtectionCleanupResult(boolean exchangeFlat, boolean cleaned) {
    }

    /**
     * 执行恢复流程使用的轻量执行状态投影。
     *
     * @param value 内部状态值
     */
    private record QuantExecutionRecordStatus(String value) {
    }

    /**
     * 单个策略 Runtime 维护的实时市场状态缓存。
     */
    private static final class MarketRuntime {
        /**
         * 当前实时 K 线快照。
         */
        private ExchangeGateway.Kline currentKline;
        /**
         * 上一根已收盘 K 线收盘价。
         */
        private BigDecimal previousClosedClose;
        /**
         * 斜率策略使用的上一根参考 K 线开盘价。
         */
        private BigDecimal previousKlineOpen;
        /**
         * 往前第二根参考 K 线开盘价。
         */
        private BigDecimal secondPreviousKlineOpen;
        /**
         * 往前第三根参考 K 线开盘价。
         */
        private BigDecimal thirdPreviousKlineOpen;
        /**
         * 最近一次收到 K 线事件的时间。
         */
        private Instant lastKlineEventAt;

        /**
         * 更新对应业务状态。
         */
        private void updateKline(ExchangeGateway.Kline kline) {
            if (kline == null) {
                return;
            }
            if (currentKline != null && kline.openTime().isBefore(currentKline.openTime())) {
                return;
            }
            if (currentKline != null && !currentKline.openTime().equals(kline.openTime())) {
                thirdPreviousKlineOpen = secondPreviousKlineOpen;
                secondPreviousKlineOpen = previousKlineOpen;
                previousKlineOpen = currentKline.open();
                if (currentKline.closed()) {
                    previousClosedClose = currentKline.close();
                }
            }
            currentKline = kline;

            if (kline.closed()) {
                previousClosedClose = kline.close();
            }
        }

        /**
         * 返回当前策略参考价格。
         */
        private BigDecimal referencePrice() {
            return previousClosedClose;
        }

        /**
         * 返回斜率计算使用的上一根参考 K 线开盘价。
         */
        private BigDecimal previousKlineOpenPrice() {
            return switch (KlineSlopeRateStrategy.SLOPE_LOOKBACK_BARS) {
                case 2 -> secondPreviousKlineOpen;
                case 3 -> thirdPreviousKlineOpen;
                default -> previousKlineOpen;
            };
        }

        /**
         * 返回当前 K 线开始时间。
         */
        private Instant currentKlineOpen() {
            return currentKline == null ? null : currentKline.openTime();
        }

        /**
         * 判断当前 K 线是否已收盘。
         */
        private boolean currentKlineClosed() {
            return currentKline != null && currentKline.closed();
        }
    }

    /**
     * 单个策略实例在当前 JVM 节点上的活动运行上下文。
     */
    private final class ActiveRuntime {
        private final DecisionLogCursor decisionLogCursor = new DecisionLogCursor();
        /**
         * 交易对，例如 BTCUSDT。
         */
        private final QuantSymbol symbol;
        /**
         * 当前实例对应的 StrategyPlugin。
         */
        private final StrategyPlugin plugin;
        /**
         * 当前策略 Runtime 的实时市场状态。
         */
        private final MarketRuntime market = new MarketRuntime();
        private final AtomicBoolean protectionSyncing = new AtomicBoolean();
        private final AtomicBoolean exitCoordinating = new AtomicBoolean();
        private final AtomicBoolean protectionRequested = new AtomicBoolean();
        private final AtomicBoolean dynamicProtectionRequested = new AtomicBoolean();
        private final AtomicBoolean reconciling = new AtomicBoolean();
        private final AtomicBoolean reconcileRequested = new AtomicBoolean();
        private final EntryRetryGate entryRetryGate = new EntryRetryGate();
        /**
         * FIXED: 仅保护模式；策略/凭证/交易对停用但仍有未平仓持仓时为 true。
         */
        private volatile boolean protectionOnly;
        /**
         * 当前策略运行实例定义。
         */
        private QuantStrategyInstance instance;
        /**
         * 当前策略定义。
         */
        private QuantStrategy strategy;
        /**
         * 当前策略交易对配置。
         */
        private QuantStrategySymbol strategySymbol;
        /**
         * 当前运行上下文使用的交易凭证。
         */
        private QuantCredential credential;
        /**
         * 当前实例合并后的策略参数。
         */
        private Map<String, Object> params;
        /**
         * 当前交易对本地持仓事实。
         */
        private volatile QuantPosition position;
        /**
         * 当前策略持仓运行状态。
         */
        private volatile QuantStrategyPositionState state;
        /**
         * 当前 Runtime 持有的行情订阅句柄。
         */
        private volatile AutoCloseable subscription;
        private volatile AutoCloseable userSubscription;
        private volatile java.util.concurrent.Future<?> protectionTask;
        private long nextPreparationNoticeAt;
        private volatile java.util.concurrent.Future<?> exitTask;
        private ExchangeGateway.SymbolRule protectionRule;
        private BigDecimal exitWindowHigh;
        private BigDecimal exitWindowLow;
        private String lastProtectionTarget;
        private long lastProtectionRequestNanos;
        private volatile boolean protectionCleanupPending;
        private boolean extremaPersistencePending;
        private volatile boolean positionReconciling;
        private volatile long nextPositionReconcilingLogAt;
        private long nextProtectionSyncAt;
        private volatile boolean protectionDegraded;
        private boolean hardStopConfirmed;
        private BigDecimal latestTradePrice;
        private Instant latestTradeEventAt;
        // 健康回调直接关闭决策，不能等待普通行情队列的断线通知。
        private volatile boolean marketStreamReady;
        private volatile long nextReconcileAt;
        /**
         * 最近一次参与开仓判断的 K 线开始时间。
         */
        private volatile Instant lastEvaluatedKlineOpenTime;
        /**
         * 下一次运行租约续期时间。
         */
        private volatile long nextLeaseRenewAt;
        /**
         * REST 账户余额查询失败后的下一次允许查询时间。
         */
        private long nextLatencyLogAt;
        private long nextPendingSlopeLogNanos;
        private Instant lastEntryAttemptEventTime;
        private BigDecimal lastEntryAttemptPrice;
        /**
         * 当前行情流是否健康。
         */
        private volatile boolean marketHealthy;
        /**
         * 组件/Runtime 是否已经关闭。
         */
        private volatile boolean closed;

        private long nextSlopeDiagLogAt;
        private long nextMarketUnreadyLogAt;
        private long marketUnreadySinceAt;

        /**
         * 创建单个策略实例的活动 Runtime 上下文。
         */
        private ActiveRuntime(QuantStrategyInstance instance, QuantStrategy strategy, QuantStrategySymbol strategySymbol, QuantCredential credential, QuantSymbol symbol,
StrategyPlugin plugin, Map<String, Object> params) {
            this.instance = instance;
            this.strategy = strategy;
            this.strategySymbol = strategySymbol;
            this.credential = credential;
            this.symbol = symbol;
            this.plugin = plugin;
            this.params = params;
            MarketRuntime initial = loadInitialMarket(credential.getRunMode(), symbol.getSymbol(), strategySymbol.getIntervalCode(), params,
             plugin instanceof KlineSlopeRateStrategy);
            this.market.previousClosedClose = initial.previousClosedClose;
            this.market.previousKlineOpen = initial.previousKlineOpen;
            this.market.secondPreviousKlineOpen = initial.secondPreviousKlineOpen;
            this.market.thirdPreviousKlineOpen = initial.thirdPreviousKlineOpen;
            this.market.currentKline = initial.currentKline;
            this.market.lastKlineEventAt = initial.lastKlineEventAt;
        }

        /**
         * 判断事件是否匹配当前订阅。
         */
        private boolean matches(String pluginCode, String symbolCode, String interval, Long credentialId) {
            return strategy.getPluginCode().equals(pluginCode) && symbol.getSymbol().equalsIgnoreCase(symbolCode) && strategySymbol.getIntervalCode().equals(interval) && credential.getId().equals(credentialId);
        }

        /**
         * 更新对应业务状态。
         */
        private void updateKline(ExchangeGateway.Kline kline) {
            market.updateKline(kline);
        }

        /**
         * 返回策略决策使用的当前实时价格。
         */
        private BigDecimal currentPrice() {
            Instant deadline = Instant.now().minusMillis(Math.max(1, properties.getMarket().getMaxEventAgeMillis()));
            if (latestTradePrice != null && latestTradeEventAt != null && !latestTradeEventAt.isBefore(deadline) && (market.lastKlineEventAt == null || !latestTradeEventAt.isBefore(market.lastKlineEventAt))) {
                return latestTradePrice;
            }
            if (market.currentKline == null) {
                return null;
            }
            return market.currentKline.close();
        }

        /**
         * 判断当前策略持仓是否存在待确认交易动作。
         */
        private boolean hasPendingAction() {
            return state != null && state.getPendingAction() != null && state.getPendingOrderTime() != null;
        }
    }
}
