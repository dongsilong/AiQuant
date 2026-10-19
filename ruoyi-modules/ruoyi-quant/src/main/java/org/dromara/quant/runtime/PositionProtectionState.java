package org.dromara.quant.runtime;

import org.dromara.quant.runtime.domain.QuantStrategyPositionState;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 持仓周期与已成交事实；不依赖环境、行情或交易所接口。
 */
public final class PositionProtectionState {
    private PositionProtectionState() {
    }

    /**
     * 初始化新持仓周期的保护与首次止盈状态。
     */
    public static void startCycle(QuantStrategyPositionState state, int leverage, BigDecimal symbolStop, BigDecimal price) {
        // A closed state row is reused for the next cycle. Clear every cycle-scoped fact before
        // assigning the new order intent, so TP/step/recovery facts cannot leak into the next position.
        state.setPositionCycleKey(UUID.randomUUID().toString());
        state.setPositionId(null);
        state.setFrozenLeverage(Math.max(1, leverage));
        state.setFrozenSymbolStopLossRatio(symbolStop);
        state.setFirstTakeProfitTargetQty(null);
        state.setFirstTakeProfitExecutedQty(BigDecimal.ZERO);
        state.setFirstTakeProfitLocalExecutedQty(BigDecimal.ZERO);
        state.setFirstTakeProfitTriggered(false);
        state.setHighestStep(0);
        state.setConfirmedProtectionStep(0);
        state.setHighestPriceSinceEntry(price);
        state.setLowestPriceSinceEntry(price);
        state.setEntryAtr(null);
        state.setPositionQty(BigDecimal.ZERO);
        state.setInitialPositionQty(BigDecimal.ZERO);
        state.setInitialMargin(BigDecimal.ZERO);
        state.setFrozenStopLossRatio(null);
        state.setStrategyParamsSnapshot(null);
        state.setDirection(null);
        state.setStopLossPrice(null);
        state.setRecoveryRequired(false);
        state.setUnsafeRecovery(false);
        state.setPendingNotFoundConfirmations(0);
        state.setOpenedAt(LocalDateTime.now());
        state.setPendingAction(null);
        state.setPendingOrderTime(null);
        state.setPendingOrderId(null);
        state.setPendingClientOrderId(null);
        state.setPendingIdempotencyKey(null);
    }

    /**
     * 累计交易所确认的首次分批止盈成交数量。
     */
    public static void confirmExchangeFirstProfit(QuantStrategyPositionState state, BigDecimal exchangeExecuted) {
        BigDecimal local = zero(state.getFirstTakeProfitLocalExecutedQty());
        state.setFirstTakeProfitExecutedQty(zero(state.getFirstTakeProfitExecutedQty()).max(local.add(zero(exchangeExecuted))));
        refreshCompleted(state);
    }

    /**
     * 累计本地模拟执行确认的首次分批止盈数量。
     */
    public static void confirmLocalFirstProfit(QuantStrategyPositionState state, BigDecimal executed) {
        state.setFirstTakeProfitLocalExecutedQty(zero(state.getFirstTakeProfitLocalExecutedQty()).add(executed));
        state.setFirstTakeProfitExecutedQty(zero(state.getFirstTakeProfitExecutedQty()).add(executed));
        refreshCompleted(state);
    }

    /**
     * 计算首次分批止盈尚未确认成交的目标数量。
     */
    public static BigDecimal remainingFirstProfit(QuantStrategyPositionState state) {
        return zero(state.getFirstTakeProfitTargetQty()).subtract(zero(state.getFirstTakeProfitExecutedQty())).max(BigDecimal.ZERO);
    }

    private static void refreshCompleted(QuantStrategyPositionState state) {
        state.setFirstTakeProfitTriggered(state.getFirstTakeProfitTargetQty() != null && state.getFirstTakeProfitTargetQty().signum() > 0 && zero(state.getFirstTakeProfitExecutedQty()).compareTo(state.getFirstTakeProfitTargetQty()) >= 0);
    }

    private static BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
