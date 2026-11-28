package org.dromara.quant.runtime;

import org.dromara.quant.strategy.KlineSlopeRateStrategy;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 双开单腿本地保护决策。ROI 与 Binance MARK_PRICE 条件单使用相同的杠杆价格回报率口径。
 */
public final class DualHedgeLocalProtectionEvaluator {
    private static final BigDecimal HARD_STOP_ROI = new BigDecimal("-0.03");
    private static final BigDecimal FIRST_TAKE_PROFIT_ROI = new BigDecimal("0.05");
    private static final BigDecimal STEP_SIZE = new BigDecimal("0.10");
    private static final BigDecimal FIRST_LOCK_ROI = new BigDecimal("0.05");
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private DualHedgeLocalProtectionEvaluator() {
    }

    /**
     * 根据最新标记价格与持仓周期最高 ROI 计算本地保护动作。
     */
    public static Decision evaluate(String direction, BigDecimal currentMarkPrice, BigDecimal entryPrice,
                                    int leverage, BigDecimal previousMaximumRoi, boolean firstTakeProfitTriggered) {
        if (!"LONG".equalsIgnoreCase(direction) && !"SHORT".equalsIgnoreCase(direction)
            || currentMarkPrice == null || currentMarkPrice.signum() <= 0
            || entryPrice == null || entryPrice.signum() <= 0
            || leverage <= 0 || previousMaximumRoi == null) {
            throw new IllegalArgumentException("双开本地保护计算参数无效");
        }
        BigDecimal currentRoi = KlineSlopeRateStrategy.leveragedReturn(
            direction, currentMarkPrice, entryPrice, leverage);
        BigDecimal maximumRoi = previousMaximumRoi.max(currentRoi);

        if (currentRoi.compareTo(HARD_STOP_ROI) <= 0) {
            return new Decision(Action.HARD_STOP, currentRoi, maximumRoi, null, 0);
        }
        if (!firstTakeProfitTriggered) {
            if (currentRoi.compareTo(FIRST_TAKE_PROFIT_ROI) >= 0) {
                return new Decision(Action.FIRST_TAKE_PROFIT, currentRoi, maximumRoi,
                    FIRST_TAKE_PROFIT_ROI, 0);
            }
            if (currentRoi.compareTo(ZERO) <= 0) {
                return new Decision(Action.RETURN_TO_ENTRY, currentRoi, maximumRoi, ZERO, 0);
            }
            return new Decision(Action.NONE, currentRoi, maximumRoi, null, 0);
        }

        int stepIndex = maximumRoi.signum() <= 0 ? 0
            : maximumRoi.divide(STEP_SIZE, 0, RoundingMode.FLOOR).intValue();
        BigDecimal lockRoi = stepIndex == 0 ? ZERO
            : stepIndex == 1 ? FIRST_LOCK_ROI
            : STEP_SIZE.multiply(BigDecimal.valueOf(stepIndex - 1L));
        if (currentRoi.compareTo(lockRoi) <= 0) {
            return new Decision(Action.TRAILING_STOP, currentRoi, maximumRoi, lockRoi, stepIndex);
        }
        return new Decision(Action.NONE, currentRoi, maximumRoi, lockRoi, stepIndex);
    }

    public enum Action {
        NONE,
        HARD_STOP,
        FIRST_TAKE_PROFIT,
        RETURN_TO_ENTRY,
        TRAILING_STOP
    }

    public record Decision(Action action, BigDecimal currentRoi, BigDecimal maximumRoi,
                           BigDecimal lockRoi, int stepIndex) {
    }
}
