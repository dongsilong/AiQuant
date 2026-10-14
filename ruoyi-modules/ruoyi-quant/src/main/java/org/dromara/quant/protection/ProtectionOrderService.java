package org.dromara.quant.protection;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.exchange.QuantityNormalizer;
import org.dromara.quant.execution.TradeExecutionApplicationService;
import org.dromara.quant.execution.LiveOrderDispatcher;
import org.dromara.quant.position.domain.QuantPosition;
import org.dromara.quant.protection.domain.QuantProtectionOrder;
import org.dromara.quant.protection.mapper.QuantProtectionOrderMapper;
import org.dromara.quant.strategy.ProtectionSignal;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 持仓保护单生命周期入口。实际保护单统一由该服务负责创建、更新、撤销和恢复。
 * 策略只给出 ProtectionSignal，不直接访问交易所。
 */
@Service
public class ProtectionOrderService {
    @org.springframework.beans.factory.annotation.Autowired
    private org.dromara.quant.decision.TradeDecisionLogService decisionLogs;

    private static final Logger log = LoggerFactory.getLogger(ProtectionOrderService.class);
    /**
     * 由策略插件动态维护的保护单类型集合。
     */
    private static final List<String> ACTIVE_STATUSES = List.of("NEW", "WORKING", "ACCEPTED", "PAPER_ACCEPTED", "CANCEL_FAILED", "PENDING", "UNKNOWN", "REJECTED");

    private static final Set<String> STRATEGY_PROTECTION_TYPES = Set.of("LOCAL_STOP", "SYMBOL_STOP", "FIRST_TP", "STEP_TP");

    /**
     * 交易所统一网关。
     */
    public static final class ProtectionExecutionPendingException extends IllegalStateException {
        public ProtectionExecutionPendingException() {
            super("保护单正在触发/已成交，等待持仓及成交对账");
        }
    }

    private final ExchangeGateway gateway;
    private volatile LiveOrderDispatcher liveOrderDispatcher;

    @org.springframework.beans.factory.annotation.Autowired
    public void setLiveOrderDispatcher(LiveOrderDispatcher dispatcher) {
        this.liveOrderDispatcher = java.util.Objects.requireNonNull(dispatcher);
    }

    private LiveOrderDispatcher requireLiveDispatcher() {
        var value = liveOrderDispatcher;
        if (value == null) throw new IllegalStateException("LIVE 保护单调度器不可用，拒绝直连交易所");
        return value;
    }
    /**
     * 当前领域 Mapper。
     */
    private final QuantProtectionOrderMapper mapper;
    /**
     * 交易执行应用服务。
     */
    private final TradeExecutionApplicationService executionApplicationService;
    private final ProtectionTargetCoordinator targets;
    private final ThreadLocal<ProtectionTargetCoordinator.Lease> targetLease = new ThreadLocal<>();

    /**
     * 创建 ProtectionOrderService 实例并初始化其依赖。
     */
    public ProtectionOrderService(ExchangeGateway gateway, QuantProtectionOrderMapper mapper, TradeExecutionApplicationService executionApplicationService) {
        this(gateway, mapper, executionApplicationService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ProtectionOrderService(ExchangeGateway gateway, QuantProtectionOrderMapper mapper,
                                  TradeExecutionApplicationService executionApplicationService, ProtectionTargetCoordinator targets) {
        this.gateway = gateway;
        this.mapper = mapper;
        this.executionApplicationService = executionApplicationService;
        this.targets = targets;
    }

    private <T> T coordinated(Long credentialId, QuantPosition position, String type, java.util.function.Supplier<T> action) {
        if (targets == null) { synchronized (this) { return action.get(); } }
        try (var lease = targets.acquire(credentialId, position, type)) {
            var previous = targetLease.get(); targetLease.set(lease);
            try { return action.get(); }
            finally { if (previous == null) { targetLease.remove(); } else { targetLease.set(previous); } }
        }
    }

    private void checkTargetLease() { if (targetLease.get() != null) { targetLease.get().check(); } }

    public int confirmedStep(Long credentialId, QuantPosition position) {
        return targets == null ? 0 : targets.confirmedStep(credentialId, position);
    }

    /**
     * 判断当前持仓是否具备策略/保护计算所需的有效字段。
     */
    private static boolean validPosition(QuantPosition position) {
        return position != null && position.getId() != null && position.getEntryPrice() != null && position.getEntryPrice().signum() > 0 && position.getQuantity() != null && position.getQuantity().signum() > 0;
    }

    /**
     * 固定硬止损。marginLossRatio 为保证金回报率亏损比例，例如 0.10 表示 -10% ROI。
     */
    public QuantProtectionOrder ensureMarginHardStop(Long credentialId, QuantPosition position, BigDecimal marginLossRatio, boolean live) {
        if (!validPosition(position)) { return null; }
        return coordinated(credentialId, position, "HARD_STOP", () -> ensureMarginHardStopTarget(credentialId, position, marginLossRatio, live));
    }

    private QuantProtectionOrder ensureMarginHardStopTarget(Long credentialId, QuantPosition position, BigDecimal marginLossRatio, boolean live) {
        if (!validPosition(position)) {
            return null;
        }
        BigDecimal quantity = normalizeQuantity(credentialId, position.getSymbol(), position.getQuantity(), live);
        BigDecimal stopPrice = normalizePrice(credentialId, position.getSymbol(), hardStopPrice(position, marginLossRatio), live);
        QuantProtectionOrder existing = findActive(credentialId, position, "HARD_STOP");
        boolean working = existing != null && (!live || confirmOnExchange(credentialId, existing, true, position.getQuantity()));
        // 固定保护价在该持仓生命周期内冻结，保证金波动不能驱动撤单重挂。
        if (working && existing.getStopPrice() != null) { stopPrice = existing.getStopPrice(); }
        if (working && targetLease.get() != null) {
            targetLease.get().confirmed(existing, 0);
            cancelSuperseded(credentialId, position.getId(), position.getProtectionCycleKey(), "HARD_STOP", existing.getId(), live);
        }
        if (working && sameTarget(existing, "STOP_MARKET", quantity, stopPrice)) {
            cancelSuperseded(credentialId, position.getId(), position.getProtectionCycleKey(), "HARD_STOP", existing.getId(), live);
            return existing;
        }
        // 新保护确认后才撤旧，下单或确认失败时保留旧保护。
        logReplacement(position, "HARD_STOP", existing, quantity, stopPrice, working);
        QuantProtectionOrder replacement = placeProtection(credentialId, position, "HARD_STOP", "STOP_MARKET", quantity, stopPrice, live);
        if (existing != null && !cancelOne(credentialId, existing, live)) {
            throw new IllegalStateException("新硬止损已生效，旧硬止损撤销失败，等待对账");
        }
        cancelSuperseded(credentialId, position.getId(), position.getProtectionCycleKey(), "HARD_STOP", replacement.getId(), live);
        return replacement;
    }

    /**
     * 创建或替换固定硬止损条件单。
     */
    public QuantProtectionOrder placeMarginHardStop(Long credentialId, QuantPosition position, BigDecimal marginLossRatio, boolean live) {
        if (!validPosition(position)) {
            return null;
        }
        return placeProtection(credentialId, position, "HARD_STOP", "STOP_MARKET", normalizeQuantity(credentialId, position.getSymbol(), position.getQuantity(), live),
            normalizePrice(credentialId, position.getSymbol(), hardStopPrice(position, marginLossRatio), live), live);
    }

    private BigDecimal hardStopPrice(QuantPosition position, BigDecimal marginLossRatio) {
        if (marginLossRatio == null || marginLossRatio.signum() <= 0 || marginLossRatio.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException("硬止损回报率必须 > 0 且 < 1");
        }
        BigDecimal price = org.dromara.quant.strategy.KlineSlopeRateStrategy.priceForReturn(position.getEntryPrice(),
            position.getPositionSide(), marginLossRatio.negate(), position.getLeverage() == null ? 1 : position.getLeverage());
        if (price.signum() <= 0) {
            throw new IllegalArgumentException("计算出的硬止损价格无效");
        }
        return price;
    }

    /**
     * 同步策略侧保护单：首次止盈前保持 LOCAL_STOP + FIRST_TP；首次止盈后切换为 STEP_TP。
     * 只有目标发生变化才撤旧挂新，高频实时价不会持续打 REST。
     */
    public void syncStrategyProtections(Long credentialId, QuantPosition position, BigDecimal initialPositionQuantity, List<ProtectionSignal> desiredSignals, boolean live) {
        syncStrategyProtections(credentialId, position, initialPositionQuantity, desiredSignals, null, live);
    }

    public void syncStrategyProtections(Long credentialId, QuantPosition position, BigDecimal initialPositionQuantity,
                                       List<ProtectionSignal> desiredSignals, BigDecimal firstRemaining, boolean live) {
        if (!validPosition(position)) {
            return;
        }
        List<ProtectionSignal> desired = desiredSignals == null ? List.of() : desiredSignals;
        if (targets != null && desired.stream().anyMatch(signal -> signal != null && "FIRST_TP".equals(signal.type()))
            && desired.stream().noneMatch(signal -> signal != null && "STEP_TP".equals(signal.type())) && confirmedStep(credentialId, position) > 0) {
            throw new IllegalStateException("已确认阶梯保护不能回退为首次止盈，先核实首平事实");
        }
        Set<String> desiredTypes = new HashSet<>();
        for (ProtectionSignal signal : desired) {
            if (signal == null || signal.type() == null || !STRATEGY_PROTECTION_TYPES.contains(signal.type())) {
                continue;
            }
            desiredTypes.add(signal.type());
            coordinated(credentialId, position, signal.type(), () -> {
                syncStrategyProtectionTarget(credentialId, position, initialPositionQuantity, signal, firstRemaining, live);
                return null;
            });
        }
        List<QuantProtectionOrder> stale = mapper.selectList(new LambdaQueryWrapper<QuantProtectionOrder>()
            .eq(QuantProtectionOrder::getCredentialId, credentialId).eq(QuantProtectionOrder::getPositionId, position.getId())
            .eq(position.getProtectionCycleKey() != null, QuantProtectionOrder::getPositionCycleKey, position.getProtectionCycleKey())
            .in(QuantProtectionOrder::getProtectionType, STRATEGY_PROTECTION_TYPES).in(QuantProtectionOrder::getStatus, ACTIVE_STATUSES));
        for (QuantProtectionOrder order : stale) {
            if (!desiredTypes.contains(order.getProtectionType()) && !cancelOne(credentialId, order, live)) {
                throw new IllegalStateException("过期策略保护单撤销失败: " + order.getProtectionType());
            }
        }
    }

    private void syncStrategyProtectionTarget(Long credentialId, QuantPosition position, BigDecimal initialPositionQuantity,
                                              ProtectionSignal signal, BigDecimal firstRemaining, boolean live) {
            BigDecimal baseQty = signal.quantityBase() == ProtectionSignal.QuantityBase.INITIAL && initialPositionQuantity != null ? initialPositionQuantity :
                position.getQuantity();
            BigDecimal targetQty = baseQty.multiply(signal.quantityRate() == null ? BigDecimal.ONE : signal.quantityRate());
            if ("FIRST_TP".equals(signal.type()) && firstRemaining != null) { targetQty = firstRemaining; }
            targetQty = targetQty.min(position.getQuantity());
            targetQty = normalizeQuantity(credentialId, position.getSymbol(), targetQty, live);
            BigDecimal triggerPrice = normalizePrice(credentialId, position.getSymbol(), signal.triggerPrice(), live);
            if (targetQty.signum() <= 0 || triggerPrice == null || triggerPrice.signum() <= 0) {
                return;
            }

            QuantProtectionOrder existing = findActive(credentialId, position, signal.type());
            boolean working = existing != null && (!live || confirmOnExchange(credentialId, existing, true, position.getQuantity()));
            if (working && existing.getStopPrice() != null && (signal.orderType() == null || signal.orderType().equalsIgnoreCase(existing.getOrderType()))) {
                if (!"STEP_TP".equals(signal.type())) {
                    triggerPrice = existing.getStopPrice();
                } else {
                    // 阶梯只能收紧，数量调整也必须保留已经锁定的更优价格。
                    triggerPrice = "SHORT".equalsIgnoreCase(position.getPositionSide())
                        ? triggerPrice.min(existing.getStopPrice()) : triggerPrice.max(existing.getStopPrice());
                }
            }
            if (working && targetLease.get() != null) {
                targetLease.get().confirmed(existing, existing.getStepIndex() == null ? 0 : existing.getStepIndex());
                cancelSuperseded(credentialId, position.getId(), position.getProtectionCycleKey(), signal.type(), existing.getId(), live);
            }
            if (working && sameTarget(existing, signal.orderType(), targetQty, triggerPrice)) {
                if (targetLease.get() != null) { targetLease.get().confirmed(existing, signal.stepIndex()); }
                cancelSuperseded(credentialId, position.getId(), position.getProtectionCycleKey(), signal.type(), existing.getId(), live);
                return;
            }
            logReplacement(position, signal.type(), existing, targetQty, triggerPrice, working);
            if ("FIRST_TP".equals(signal.type()) && existing != null) {
                // 分批止盈先停旧单。全量止损的“先新后旧”不能用于固定首平目标，否则撤单竞争会重复减仓。
                if (!cancelOne(credentialId, existing, live)) { throw new IllegalStateException("首次止盈旧单撤销结果未知，等待恢复"); }
                if (existing.getExecutedQuantity() != null && existing.getExecutedQuantity().signum() > 0) {
                    throw new ProtectionExecutionPendingException();
                }
                existing = null;
            }
            QuantProtectionOrder replacement = placeProtection(credentialId, position, signal.type(), signal.orderType(), targetQty, triggerPrice, live, signal.stepIndex());
            if (existing != null && !cancelOne(credentialId, existing, live)) {
                throw new IllegalStateException("新保护已生效，旧保护撤销失败: " + signal.type());
            }
            cancelSuperseded(credentialId, position.getId(), position.getProtectionCycleKey(), signal.type(), replacement.getId(), live);
    }

    private QuantProtectionOrder placeProtection(Long credentialId, QuantPosition position, String protectionType, String orderType, BigDecimal quantity, BigDecimal stopPrice,
                                                 boolean live) {
        return placeProtection(credentialId, position, protectionType, orderType, quantity, stopPrice, live, 0);
    }

    private QuantProtectionOrder placeProtection(Long credentialId, QuantPosition position, String protectionType, String orderType,
                                                 BigDecimal quantity, BigDecimal stopPrice, boolean live, int stepIndex) {
        ExchangeGateway.Credential credential = executionApplicationService.toCredential(executionApplicationService.requireCredential(credentialId));
        String exchangePositionSide = resolveExchangePositionSide(credential, position.getPositionSide());
        String prefix = switch (protectionType) {
            case "HARD_STOP" -> "QHARD_";
            case "SYMBOL_STOP" -> "QSYM_";
            case "FIRST_TP" -> "QFTP_";
            case "STEP_TP" -> "QSTEP_";
            default -> "QSTOP_";
        };
        // 标识先写入持久化意图，超时后沿用该标识精确查询，禁止换标识盲目重试。
        String clientAlgoId = prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 28);
        QuantProtectionOrder entity = new QuantProtectionOrder();
        entity.setCredentialId(credentialId);
        entity.setPositionId(position.getId());
        entity.setPositionCycleKey(position.getProtectionCycleKey());
        entity.setExecutedQuantity(BigDecimal.ZERO);
        entity.setSymbol(position.getSymbol());
        entity.setPositionSide(position.getPositionSide());
        entity.setClientOrderId(clientAlgoId);
        entity.setProtectionType(protectionType);
        entity.setOrderType(orderType == null ? "STOP_MARKET" : orderType);
        entity.setQuantity(quantity);
        entity.setStopPrice(stopPrice);
        entity.setStatus("PENDING");
        entity.setStepIndex(stepIndex);
        if (targetLease.get() == null) {
            if (mapper.insert(entity) != 1 || entity.getId() == null) { throw new IllegalStateException("保护单意图未可靠持久化，拒绝发送交易所请求"); }
        } else {
            QuantProtectionOrder previous = findActive(credentialId, position, protectionType);
            targetLease.get().prepare(entity, stepIndex, previous, row -> {
                if (mapper.insert(row) != 1) { throw new IllegalStateException("保护意图持久化失败"); }
            });
        }
        log.info("止盈止损提交: accountId={}, positionId={}, symbol={}, direction={}, protectionType={}, orderType={}, quantity={}, triggerPrice={}, clientAlgoId={}, live={}",
            credentialId, position.getId(), position.getSymbol(), position.getPositionSide(), protectionType, entity.getOrderType(), quantity, stopPrice, clientAlgoId, live);
        try {
            checkTargetLease();
            ExchangeGateway.ProtectionOrderResult result = submitProtected(credentialId, entity, new ExchangeGateway.ProtectionOrderRequest(credential, position.getSymbol(),
                "SHORT".equalsIgnoreCase(position.getPositionSide()) ? "BUY" : "SELL", exchangePositionSide, entity.getOrderType(), quantity, stopPrice, clientAlgoId, live));
            entity.setExchangeOrderId(result.orderId());
            if (!live) {
                entity.setStatus("PAPER_ACCEPTED");
                mapper.updateById(entity);
                log.info("止盈止损模拟设置完成: symbol={}, protectionType={}, clientAlgoId={}, quantity={}, triggerPrice={}",
                    entity.getSymbol(), protectionType, clientAlgoId, quantity, stopPrice);
            } else if (!confirmOnExchange(credentialId, entity)) {
                throw new IllegalStateException("Binance 保护单尚未确认生效");
            }
        } catch (RuntimeException ex) {
            entity.setStatus(ex instanceof org.dromara.quant.exchange.ExchangeTradingPort.OrderRejectedException ? "REJECTED" : "UNKNOWN");
            log.warn("止盈止损提交或确认失败，结果待对账: accountId={}, positionId={}, symbol={}, protectionType={}, clientAlgoId={}, exchangeOrderId={}, quantity={}, triggerPrice={}, errorType={}",
                credentialId, position.getId(), position.getSymbol(), protectionType, clientAlgoId, entity.getExchangeOrderId(), quantity, stopPrice, ex.getClass().getSimpleName());
            mapper.updateById(entity);
            if (confirmUncertainSubmission(credentialId, entity, ex)) {
                if (targetLease.get() != null) { targetLease.get().confirmed(entity, stepIndex); }
                return entity;
            }
            throw ex;
        }
        if (targetLease.get() != null) { targetLease.get().confirmed(entity, stepIndex); }
        return entity;
    }

    /**
     * The primary LIVE protection order path MUST use the same durable account-symbol
     * dispatch guard as ordinary orders. Acknowledge only after the accepted Binance
     * algo order ID is persisted; unknown outcomes keep the guard occupied.
     */
    private ExchangeGateway.ProtectionOrderResult submitProtected(Long credentialId,
                                                                  QuantProtectionOrder local,
                                                                  ExchangeGateway.ProtectionOrderRequest request) {
        if (!request.live()) return gateway.placeProtectionOrder(request);
        var dispatcher = requireLiveDispatcher();
        var ticket = dispatcher.begin(credentialId, local.getSymbol(),
            "PROT_CREATE:" + local.getId() + ":" + UUID.randomUUID(),
            "PROTECT_CREATE", local.getClientOrderId(), null, null);
        boolean attempted = false;
        boolean finished = false;
        try {
            attempted = true;
            var result = gateway.placeProtectionOrder(request);
            if (result == null || result.orderId() == null || result.orderId() <= 0
                || !local.getClientOrderId().equals(result.clientOrderId())) {
                throw new IllegalStateException("Binance 保护单回执缺少准确订单 ID 或客户端标识");
            }
            local.setExchangeOrderId(result.orderId());
            if (mapper.updateById(local) != 1) {
                throw new IllegalStateException("Binance 已接受保护单，但本地意图未可靠写入");
            }
            dispatcher.acknowledged(ticket, result.orderId());
            finished = true;
            return result;
        } catch (RuntimeException ex) {
            if (!finished) {
                try {
                    if (attempted && (ex instanceof org.dromara.quant.exchange.ExchangeTradingPort.OrderNotSentException
                        || ex instanceof org.dromara.quant.exchange.ExchangeTradingPort.OrderRejectedException)) {
                        dispatcher.notSent(ticket);
                    } else {
                        dispatcher.unknown(ticket);
                    }
                } catch (RuntimeException dispatchError) {
                    ex.addSuppressed(dispatchError);
                }
            }
            throw ex;
        }
    }

    /**
     * A cancel remains UNKNOWN unless an exact exchange query proves a terminal
     * outcome and that fact has been persisted in the local protection ledger.
     */
    private boolean cancelProtected(Long credentialId, QuantProtectionOrder local, boolean live,
                                    Set<String> allowedTerminalStatuses, boolean requireUnfilled) {
        var credential = executionApplicationService.toCredential(executionApplicationService.requireCredential(credentialId));
        if (!live) {
            gateway.cancelProtectionOrder(new ExchangeGateway.CancelProtectionOrderRequest(
                credential, local.getExchangeOrderId(), local.getClientOrderId(), false));
            local.setStatus("CANCELED");
            return mapper.updateById(local) == 1;
        }
        var dispatcher = requireLiveDispatcher();
        var ticket = dispatcher.begin(credentialId, local.getSymbol(),
            "PROT_CANCEL:" + local.getId() + ":" + UUID.randomUUID(),
            "PROTECT_CANCEL", local.getClientOrderId(), null, null);
        boolean finished = false;
        try {
            try {
                gateway.cancelProtectionOrder(new ExchangeGateway.CancelProtectionOrderRequest(
                    credential, local.getExchangeOrderId(), local.getClientOrderId(), true));
            } catch (RuntimeException ex) {
                // A cancel can race with a fill: the exact Binance snapshot decides,
                // not the transport exception or absence from an open-order list.
            }
            var actual = readProtectionSnapshot(credential, local);
            if (actual == null || !allowedTerminalStatuses.contains(actual.status())
                || actual.executedQuantity() == null
                || requireUnfilled && actual.executedQuantity().signum() != 0
                || actual.algoId() == null || actual.algoId() <= 0
                || !local.getClientOrderId().equals(actual.clientAlgoId())) {
                return false;
            }
            if (decisionLogs != null) decisionLogs.protection(credentialId, local, actual);
            local.setStatus(actual.status());
            local.setExecutedQuantity(actual.executedQuantity()
                .max(local.getExecutedQuantity() == null ? BigDecimal.ZERO : local.getExecutedQuantity()));
            if (mapper.updateById(local) != 1) {
                throw new IllegalStateException("保护撤单终态未可靠写入本地账本");
            }
            dispatcher.acknowledged(ticket, actual.algoId());
            finished = true;
            return true;
        } catch (RuntimeException ex) {
            log.warn("保护撤单终态仍无法确认: accountId={}, symbol={}, clientAlgoId={}, errorType={}",
                credentialId, local.getSymbol(), local.getClientOrderId(), ex.getClass().getSimpleName());
            return false;
        } finally {
            if (!finished) {
                try { dispatcher.unknown(ticket); }
                catch (RuntimeException ex) {
                    log.error("保护撤单调度结果未知，账户交易对必须保持阻断: accountId={}, symbol={}",
                        credentialId, local.getSymbol(), ex);
                }
            }
        }
    }

    /** 包括重复客户端标识在内的未知提交结果，只精确查询原意图，不再次发送。 */
    private boolean confirmUncertainSubmission(Long credentialId, QuantProtectionOrder local, RuntimeException failure) {
        if (!(failure instanceof org.dromara.quant.exchange.ExchangeTradingPort.ExchangeRequestException)
            || failure instanceof org.dromara.quant.exchange.ExchangeTradingPort.OrderRejectedException) { return false; }
        try {
            return confirmOnExchange(credentialId, local, false);
        } catch (ProtectionExecutionPendingException pending) {
            throw pending;
        } catch (RuntimeException unresolved) {
            return false;
        }
    }

    /** 确认实际订单字段，未知结果不得当作保护生效。 */
    private boolean confirmOnExchange(Long credentialId, QuantProtectionOrder local) {
        return confirmOnExchange(credentialId, local, true);
    }

    private boolean confirmOnExchange(Long credentialId, QuantProtectionOrder local, boolean allowResubmit) {
        return confirmOnExchange(credentialId, local, allowResubmit, null);
    }

    private boolean confirmOnExchange(Long credentialId, QuantProtectionOrder local, boolean allowResubmit, BigDecimal currentQuantity) {
        ExchangeGateway.Credential credential = executionApplicationService.toCredential(executionApplicationService.requireCredential(credentialId));
        ExchangeGateway.ProtectionOrderSnapshot actual;
        boolean definitelyAbsent = false;
        try {
            actual = readProtectionSnapshot(credential, local);
        } catch (org.dromara.quant.exchange.ExchangeTradingPort.ProtectionOrderAbsentException absent) {
            definitelyAbsent = true;
            if (allowResubmit && ("PENDING".equals(local.getStatus()) || "UNKNOWN".equals(local.getStatus()) || "REJECTED".equals(local.getStatus()))) {
                local.setQuantity(normalizeQuantity(credentialId, local.getSymbol(), currentQuantity == null ? local.getQuantity()
                    : local.getQuantity().min(currentQuantity), true));
                local.setStopPrice(normalizePrice(credentialId, local.getSymbol(), local.getStopPrice(), true));
                local.setStatus("PENDING");
                if (mapper.updateById(local) != 1) {
                    throw new IllegalStateException("恢复保护单意图未可靠持久化，拒绝重送");
                }
                // 明确不存在才允许沿用持久化标识重送；不换标识，不生成第二份意图。
                log.info("止盈止损明确不存在，沿用原标识重送: accountId={}, symbol={}, protectionType={}, clientAlgoId={}, quantity={}, triggerPrice={}",
                    credentialId, local.getSymbol(), local.getProtectionType(), local.getClientOrderId(), local.getQuantity(), local.getStopPrice());
                String exchangeSide = resolveExchangePositionSide(credential, local.getPositionSide());
                ExchangeGateway.ProtectionOrderResult result;
                try {
                    checkTargetLease();
                    result = submitProtected(credentialId, local, new ExchangeGateway.ProtectionOrderRequest(credential, local.getSymbol(),
                        "SHORT".equalsIgnoreCase(local.getPositionSide()) ? "BUY" : "SELL", exchangeSide, local.getOrderType(), local.getQuantity(), local.getStopPrice(), local.getClientOrderId(), true));
                } catch (RuntimeException ex) {
                    local.setStatus(ex instanceof org.dromara.quant.exchange.ExchangeTradingPort.OrderRejectedException ? "REJECTED" : "UNKNOWN");
                    mapper.updateById(local);
                    if (confirmUncertainSubmission(credentialId, local, ex)) { return true; }
                    throw ex;
                }
                local.setExchangeOrderId(result.orderId());
                mapper.updateById(local);
                actual = readProtectionSnapshot(credential, local);
            } else { actual = null; }
        }
        if (actual == null) {
            if (!definitelyAbsent) { throw new IllegalStateException("保护单查询未返回明确结果，禁止按缺单重挂"); }
            if ("PENDING".equals(local.getStatus()) || "UNKNOWN".equals(local.getStatus())) {
                throw new IllegalStateException("保护单提交结果未知，保留原意图等待精确查询: " + local.getClientOrderId());
            }
            local.setStatus("MISSING");
            mapper.updateById(local);
            if (targetLease.get() != null) { targetLease.get().terminal(local); }
            return false;
        }
        if (decisionLogs != null) { decisionLogs.protection(credentialId, local, actual); }
        local.setExchangeOrderId(actual.algoId());
        if (Set.of("TRIGGERING", "TRIGGERED", "FINISHED").contains(actual.status())
            || actual.executedQuantity() != null && actual.executedQuantity().signum() > 0) {
            throw new ProtectionExecutionPendingException();
        }
        if (!Set.of("NEW", "WORKING", "ACCEPTED").contains(actual.status())) {
            if (!"REJECTED".equals(actual.status()) && actual.executedQuantity() == null) { throw new ProtectionExecutionPendingException(); }
            local.setStatus(actual.status());
            mapper.updateById(local);
            if (targetLease.get() != null) { targetLease.get().terminal(local); }
            return false;
        }
        boolean matching = actual.type() != null && sameTarget(local, actual.type(), actual.quantity(), actual.triggerPrice())
            && ("SHORT".equalsIgnoreCase(local.getPositionSide()) ? "BUY" : "SELL").equalsIgnoreCase(actual.side())
            && ("BOTH".equalsIgnoreCase(actual.positionSide()) || local.getPositionSide().equalsIgnoreCase(actual.positionSide()));
        if (!matching) {
            throw new IllegalStateException("Binance 保护单字段与持久化意图不一致");
        }
        boolean newlyConfirmed = !"WORKING".equals(local.getStatus());
        local.setStatus("WORKING");
        mapper.updateById(local);
        if (newlyConfirmed) {
            log.info("止盈止损交易所确认生效: accountId={}, positionId={}, symbol={}, protectionType={}, exchangeOrderId={}, clientAlgoId={}, quantity={}, triggerPrice={}, exchangeStatus={}",
                credentialId, local.getPositionId(), local.getSymbol(), local.getProtectionType(), actual.algoId(), local.getClientOrderId(), actual.quantity(), actual.triggerPrice(), actual.status());
        }
        return true;
    }

    private void cancelSuperseded(Long credentialId, Long positionId, String cycleKey, String type, Long keepId, boolean live) {
        List<QuantProtectionOrder> older = mapper.selectList(new LambdaQueryWrapper<QuantProtectionOrder>()
            .eq(QuantProtectionOrder::getCredentialId, credentialId).eq(QuantProtectionOrder::getPositionId, positionId)
            .eq(cycleKey != null, QuantProtectionOrder::getPositionCycleKey, cycleKey)
            .eq(QuantProtectionOrder::getProtectionType, type).ne(QuantProtectionOrder::getId, keepId)
            .in(QuantProtectionOrder::getStatus, ACTIVE_STATUSES));
        for (QuantProtectionOrder order : older) {
            if (!cancelOne(credentialId, order, live)) { throw new IllegalStateException("旧保护单仍需撤销对账"); }
        }
        if (targetLease.get() != null) { targetLease.get().cleaned(); }
    }

    private void logReplacement(QuantPosition position, String type, QuantProtectionOrder old, BigDecimal quantity, BigDecimal price, boolean working) {
        String reason = old == null ? "首次挂载" : !working ? "交易所订单失效"
            : old.getQuantity().compareTo(quantity) != 0 ? "持仓数量变化" : "阶梯升级或订单类型变化";
        log.info("止盈止损目标更新: positionId={}, symbol={}, direction={}, protectionType={}, reason={}, oldOrderId={}, oldQuantity={}, newQuantity={}, oldTriggerPrice={}, newTriggerPrice={}",
            position.getId(), position.getSymbol(), position.getPositionSide(), type, reason, old == null ? null : old.getExchangeOrderId(),
            old == null ? null : old.getQuantity(), quantity, old == null ? null : old.getStopPrice(), price);
    }

    private ExchangeGateway.SymbolRule rule(Long credentialId, String symbol) {
        ExchangeGateway.Credential credential = executionApplicationService.toCredential(executionApplicationService.requireCredential(credentialId));
        // 网关按 LIVE/TESTNET 缓存实际规则，保护单不能复用本地导入的交易对精度。
        return gateway.queryTradingRule(credential, symbol);
    }

    /** 按实际交易环境的数量步长标准化保护单。 */
    private BigDecimal normalizeQuantity(Long credentialId, String symbol, BigDecimal quantity, boolean live) {
        return live ? QuantityNormalizer.quantity(quantity, rule(credentialId, symbol), "MARKET") : quantity;
    }

    /** 按实际交易环境的 PRICE_FILTER 标准化触发价。 */
    private BigDecimal normalizePrice(Long credentialId, String symbol, BigDecimal price, boolean live) {
        return price == null || !live ? price : QuantityNormalizer.price(price, rule(credentialId, symbol));
    }

    /**
     * 判断现有保护单是否与目标订单类型、数量和触发价一致。
     */
    private boolean sameTarget(QuantProtectionOrder existing, String orderType, BigDecimal quantity, BigDecimal triggerPrice) {
        if (existing == null || existing.getQuantity() == null || existing.getStopPrice() == null || quantity == null || triggerPrice == null) {
            return false;
        }
        return (orderType == null || orderType.equalsIgnoreCase(existing.getOrderType())) && existing.getQuantity().compareTo(quantity) == 0 && existing.getStopPrice().compareTo(triggerPrice) == 0;
    }

    /**
     * 查询指定持仓当前有效的保护单。
     */
    private QuantProtectionOrder findActive(Long credentialId, QuantPosition position, String type) {
        if (targetLease.get() != null && targetLease.get().state().pendingOrderId() != null) {
            var pending = mapper.selectById(targetLease.get().state().pendingOrderId());
            if (pending == null || !credentialId.equals(pending.getCredentialId())
                || !position.getProtectionCycleKey().equals(pending.getPositionCycleKey()) || !type.equals(pending.getProtectionType())) {
                throw new IllegalStateException("持久化待确认保护意图关联不一致，禁止新建");
            }
            if (ACTIVE_STATUSES.contains(pending.getStatus())) { return pending; }
            if (pending.getExecutedQuantity() != null && pending.getExecutedQuantity().signum() > 0) { throw new ProtectionExecutionPendingException(); }
            targetLease.get().terminal(pending);
        }
        var query = new LambdaQueryWrapper<QuantProtectionOrder>().eq(QuantProtectionOrder::getCredentialId, credentialId)
            .eq(QuantProtectionOrder::getPositionId, position.getId()).eq(position.getProtectionCycleKey() != null, QuantProtectionOrder::getPositionCycleKey, position.getProtectionCycleKey())
            .eq(QuantProtectionOrder::getProtectionType, type).in(QuantProtectionOrder::getStatus, ACTIVE_STATUSES).orderByDesc(QuantProtectionOrder::getId);
        var candidates = mapper.selectList(query).stream().filter(order -> type.equals(order.getProtectionType())).toList();
        if (candidates.isEmpty()) {
            return targets == null ? mapper.selectOne(query.last("LIMIT 1")) : null;
        }
        if ("FIRST_TP".equals(type)) { return candidates.getFirst(); }
        // 同周期止损重复单优先保留已验证的最严格目标，不能因ID较新选中更弱的保护。
        return candidates.stream().max((left, right) -> {
            int price = left.getStopPrice() == null ? -1 : right.getStopPrice() == null ? 1
                : "SHORT".equalsIgnoreCase(position.getPositionSide()) ? right.getStopPrice().compareTo(left.getStopPrice()) : left.getStopPrice().compareTo(right.getStopPrice());
            return price != 0 ? price : Long.compare(left.getId(), right.getId());
        }).orElseThrow();
    }

    /**
     * 撤销单张保护单并更新本地状态。
     */
    private boolean cancelOne(Long credentialId, QuantProtectionOrder order, boolean live) {
        if (targets != null && targetLease.get() == null) {
            QuantPosition scope = new QuantPosition(); scope.setId(order.getPositionId()); scope.setPositionSide(order.getPositionSide()); scope.setProtectionCycleKey(order.getPositionCycleKey());
            return coordinated(credentialId, scope, order.getProtectionType(), () -> cancelOne(credentialId, order, live));
        }
        checkTargetLease();
        if ("MISSING".equals(order.getStatus())) { return true; }
        if (cancelProtected(credentialId, order, live,
            Set.of("CANCELED", "EXPIRED", "REJECTED", "FINISHED"), false)) {
            return true;
        }
        order.setStatus("CANCEL_FAILED");
        mapper.updateById(order);
        return false;
    }

    /** 条件单触发后，普通子订单也必须确认终态；不能仅凭算法订单 FINISHED 再发减仓。 */
    private ExchangeGateway.ProtectionOrderSnapshot readProtectionSnapshot(ExchangeGateway.Credential credential, QuantProtectionOrder order) {
        ExchangeGateway.ProtectionOrderSnapshot actual = gateway.queryProtectionOrder(credential, order.getExchangeOrderId(), order.getClientOrderId(), order.getSymbol());
        if (actual == null || actual.actualOrderId() == null || actual.actualOrderId() <= 0) { return actual; }
        ExchangeGateway.OrderSnapshot child = gateway.queryOrder(credential, order.getSymbol(), actual.actualOrderId(), null);
        if (child == null || child.executedQuantity() == null || !Set.of("FILLED", "CANCELED", "EXPIRED", "REJECTED").contains(child.status())) {
            throw new ProtectionExecutionPendingException();
        }
        return new ExchangeGateway.ProtectionOrderSnapshot(actual.symbol(), actual.algoId(), actual.clientAlgoId(), "FINISHED", actual.side(),
            actual.positionSide(), actual.type(), actual.quantity(), actual.triggerPrice(), actual.updateTime(), child.executedQuantity(),
            actual.reduceOnly(), actual.closePosition(), actual.actualOrderId(), child.averagePrice());
    }

    /**
     * 根据账户持仓模式映射交易所 positionSide。
     */
    private String resolveExchangePositionSide(ExchangeGateway.Credential credential, String localDirection) {
        if ("PAPER".equalsIgnoreCase(credential.runMode())) {
            return localDirection;
        }
        return gateway.queryPositionMode(credential).hedgeMode() ? localDirection : "BOTH";
    }

    /**
     * 判断指定策略保护是否仍真实存在于交易所。PAPER 返回 false，确保本地撮合/退出逻辑照常执行。
     */
    public boolean isStrategyProtectionWorking(Long credentialId, QuantPosition position, String protectionType, boolean live) {
        if (!live || position == null || position.getId() == null || protectionType == null || protectionType.isBlank()) {
            return false;
        }
        QuantProtectionOrder local = findActive(credentialId, position, protectionType);
        if (local == null) {
            return false;
        }
        try {
            return confirmOnExchange(credentialId, local, false);
        } catch (ProtectionExecutionPendingException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            // 无法确认时交由本地退出流程刷新持仓，再决定是否减仓。
            return false;
        }
    }

    /**
     * 判断固定硬止损是否处于有效状态。
     */
    public boolean isHardStopWorking(Long credentialId, QuantPosition position) {
        if (position == null || position.getId() == null) {
            return false;
        }
        return isStrategyProtectionWorking(credentialId, position, "HARD_STOP", true);
    }

    /** 部分减仓 fallback 前必须确认对应条件单已撤销，避免双重首止盈。 */
    public boolean cancelStrategyProtection(Long credentialId, QuantPosition position, String type) {
        QuantProtectionOrder local = mapper.selectOne(new LambdaQueryWrapper<QuantProtectionOrder>()
            .eq(QuantProtectionOrder::getCredentialId, credentialId).eq(QuantProtectionOrder::getPositionId, position.getId())
            .eq(QuantProtectionOrder::getProtectionType, type).orderByDesc(QuantProtectionOrder::getId).last("LIMIT 1"));
        if (local == null) { return true; }
        // Cancel + exact terminal proof + durable state update execute under one dispatch ticket.
        return cancelProtected(credentialId, local, true, Set.of("CANCELED", "EXPIRED", "REJECTED"), true);
    }

    /** 精确读取本周期全部首止盈订单的成交量；重挂后的成交也保留，人工减仓不计入。 */
    public BigDecimal firstTakeProfitExecutedQuantity(Long credentialId, QuantPosition position) {
        List<QuantProtectionOrder> orders = mapper.selectList(new LambdaQueryWrapper<QuantProtectionOrder>()
            .eq(QuantProtectionOrder::getCredentialId, credentialId).eq(QuantProtectionOrder::getPositionId, position.getId())
            .eq(QuantProtectionOrder::getPositionCycleKey, position.getProtectionCycleKey())
            .eq(QuantProtectionOrder::getProtectionType, "FIRST_TP"));
        ExchangeGateway.Credential credential = executionApplicationService.toCredential(executionApplicationService.requireCredential(credentialId));
        BigDecimal total = BigDecimal.ZERO;
        for (QuantProtectionOrder order : orders) {
            ExchangeGateway.ProtectionOrderSnapshot actual = readProtectionSnapshot(credential, order);
            if (actual == null || Set.of("TRIGGERING", "TRIGGERED").contains(actual.status())) {
                throw new ProtectionExecutionPendingException();
            }
            if (actual.executedQuantity() == null && !Set.of("NEW", "WORKING", "ACCEPTED").contains(actual.status())) {
                throw new ProtectionExecutionPendingException();
            }
            BigDecimal executed = actual.executedQuantity() == null ? BigDecimal.ZERO : actual.executedQuantity();
            order.setExecutedQuantity(executed.max(order.getExecutedQuantity() == null ? BigDecimal.ZERO : order.getExecutedQuantity()));
            order.setStatus(actual.status());
            mapper.updateById(order);
            total = total.add(order.getExecutedQuantity());
        }
        return total;
    }

    /** 本地兜底前停止本周期本系统所有条件单，精确终态确认后才允许刷新持仓并补平。 */
    public boolean quiesceCycleProtections(Long credentialId, QuantPosition position) {
        List<QuantProtectionOrder> orders = mapper.selectList(new LambdaQueryWrapper<QuantProtectionOrder>()
            .eq(QuantProtectionOrder::getCredentialId, credentialId).eq(QuantProtectionOrder::getPositionId, position.getId())
            .eq(QuantProtectionOrder::getPositionCycleKey, position.getProtectionCycleKey())
            .in(QuantProtectionOrder::getProtectionType, "HARD_STOP", "LOCAL_STOP", "SYMBOL_STOP", "FIRST_TP", "STEP_TP")
            .in(QuantProtectionOrder::getStatus, ACTIVE_STATUSES));
        for (QuantProtectionOrder order : orders) {
            if (!cancelProtected(credentialId, order, true,
                Set.of("CANCELED", "EXPIRED", "REJECTED", "FINISHED"), false)) {
                return false;
            }
        }
        return true;
    }

    /** 首止盈只由该持仓关联条件单的真实成交事实确认，不以普通减仓推断。 */
    public boolean isFirstTakeProfitFilled(Long credentialId, QuantPosition position, BigDecimal initialQuantity) {
        QuantProtectionOrder local = mapper.selectOne(new LambdaQueryWrapper<QuantProtectionOrder>()
            .eq(QuantProtectionOrder::getCredentialId, credentialId).eq(QuantProtectionOrder::getPositionId, position.getId())
            .eq(QuantProtectionOrder::getProtectionType, "FIRST_TP").orderByDesc(QuantProtectionOrder::getId).last("LIMIT 1"));
        if (local == null) { return false; }
        ExchangeGateway.Credential credential = executionApplicationService.toCredential(executionApplicationService.requireCredential(credentialId));
        ExchangeGateway.ProtectionOrderSnapshot actual;
        try {
            actual = readProtectionSnapshot(credential, local);
        } catch (org.dromara.quant.exchange.ExchangeTradingPort.ProtectionOrderAbsentException absent) {
            // 不存在不是成交证明；提交结果未知的原意图仍交由保护恢复流程精确处理。
            return false;
        }
        if (actual == null) { return false; }
        if (decisionLogs != null) { decisionLogs.protection(credentialId, local, actual); }
        if ("FINISHED".equalsIgnoreCase(actual.status()) && actual.executedQuantity() != null && actual.executedQuantity().signum() > 0
            && local.getQuantity() != null && actual.executedQuantity().compareTo(local.getQuantity()) >= 0
            && initialQuantity != null && position.getQuantity() != null
            && position.getQuantity().compareTo(initialQuantity.subtract(actual.executedQuantity()).max(BigDecimal.ZERO)) <= 0) {
            local.setStatus("FINISHED");
            mapper.updateById(local);
            return true;
        }
        return false;
    }

    /**
     * 撤销指定持仓的全部活动保护单。
     */
    public boolean cancelActiveProtectionOrders(Long credentialId, Long positionId, boolean live) {
        return cancelActiveProtectionOrders(credentialId, positionId, null, live);
    }

    public boolean cancelActiveProtectionOrders(Long credentialId, Long positionId, String cycleKey, boolean live) {
        if (credentialId == null || positionId == null) {
            return true;
        }
        List<QuantProtectionOrder> orders =
            mapper.selectList(new LambdaQueryWrapper<QuantProtectionOrder>().eq(QuantProtectionOrder::getCredentialId, credentialId).eq(QuantProtectionOrder::getPositionId,
                positionId).eq(cycleKey != null, QuantProtectionOrder::getPositionCycleKey, cycleKey).in(QuantProtectionOrder::getProtectionType, "HARD_STOP", "LOCAL_STOP", "SYMBOL_STOP", "FIRST_TP", "STEP_TP")
                .in(QuantProtectionOrder::getStatus, ACTIVE_STATUSES));
        boolean success = true;
        for (QuantProtectionOrder order : orders) {
            success &= cancelOne(credentialId, order, live);
        }
        return success;
    }

    /**
     * 撤销无法关联有效持仓的孤儿保护单。
     */
    public boolean cancelOrphanProtectionOrders(Long credentialId, boolean live) {
        if (credentialId == null) {
            return true;
        }
        List<QuantProtectionOrder> orders =
            mapper.selectList(new LambdaQueryWrapper<QuantProtectionOrder>().eq(QuantProtectionOrder::getCredentialId, credentialId).isNull(QuantProtectionOrder::getPositionId).in(QuantProtectionOrder::getStatus, "NEW", "WORKING", "ACCEPTED"));
        boolean success = true;
        for (QuantProtectionOrder order : orders) {
            success &= cancelOne(credentialId, order, live);
        }
        return success;
    }

}
