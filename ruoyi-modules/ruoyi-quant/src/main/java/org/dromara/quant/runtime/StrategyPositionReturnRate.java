package org.dromara.quant.runtime;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Calculates the position return rate using the current mark-price notional and leverage basis. */
public final class StrategyPositionReturnRate {
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    private StrategyPositionReturnRate() {
    }

    public static BigDecimal calculate(String direction, BigDecimal quantity, BigDecimal entryPrice, BigDecimal markPrice, Integer leverage) {
        if (quantity == null || quantity.signum() == 0 || entryPrice == null || entryPrice.signum() <= 0 || markPrice == null || markPrice.signum() <= 0
            || leverage == null || leverage <= 0) {
            return null;
        }
        boolean shortPosition = "SHORT".equalsIgnoreCase(direction) || quantity.signum() < 0;
        BigDecimal signedMove = shortPosition ? entryPrice.subtract(markPrice) : markPrice.subtract(entryPrice);
        BigDecimal pnl = signedMove.multiply(quantity.abs());
        BigDecimal margin = markPrice.multiply(quantity.abs()).divide(BigDecimal.valueOf(leverage), 16, RoundingMode.HALF_UP);
        if (margin.signum() <= 0) {
            return null;
        }
        return pnl.divide(margin, 10, RoundingMode.HALF_UP).multiply(ONE_HUNDRED);
    }
}
