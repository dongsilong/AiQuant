package org.dromara.quant.runtime;

import org.dromara.quant.runtime.domain.QuantStrategyPositionState;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/** 持仓周期与已成交事实；不依赖环境、行情或交易所接口。 */
public final class PositionProtectionState {
    private PositionProtectionState() {}

    public static void startCycle(QuantStrategyPositionState state, int leverage, BigDecimal symbolStop, BigDecimal price) {
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
        state.setStopLossPrice(null);
        state.setOpenedAt(LocalDateTime.now());
        state.setPendingAction(null);
        state.setPendingOrderTime(null);
        state.setPendingOrderId(null);
        state.setPendingClientOrderId(null);
        state.setPendingIdempotencyKey(null);
    }

    public static void confirmExchangeFirstProfit(QuantStrategyPositionState state, BigDecimal exchangeExecuted) {
        BigDecimal local = zero(state.getFirstTakeProfitLocalExecutedQty());
        state.setFirstTakeProfitExecutedQty(zero(state.getFirstTakeProfitExecutedQty()).max(local.add(zero(exchangeExecuted))));
        refreshCompleted(state);
    }

    public static void confirmLocalFirstProfit(QuantStrategyPositionState state, BigDecimal executed) {
        state.setFirstTakeProfitLocalExecutedQty(zero(state.getFirstTakeProfitLocalExecutedQty()).add(executed));
        state.setFirstTakeProfitExecutedQty(zero(state.getFirstTakeProfitExecutedQty()).add(executed));
        refreshCompleted(state);
    }

    public static BigDecimal remainingFirstProfit(QuantStrategyPositionState state) {
        return zero(state.getFirstTakeProfitTargetQty()).subtract(zero(state.getFirstTakeProfitExecutedQty())).max(BigDecimal.ZERO);
    }

    private static void refreshCompleted(QuantStrategyPositionState state) {
        state.setFirstTakeProfitTriggered(state.getFirstTakeProfitTargetQty() != null && state.getFirstTakeProfitTargetQty().signum() > 0
            && zero(state.getFirstTakeProfitExecutedQty()).compareTo(state.getFirstTakeProfitTargetQty()) >= 0);
    }

    private static BigDecimal zero(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
}
