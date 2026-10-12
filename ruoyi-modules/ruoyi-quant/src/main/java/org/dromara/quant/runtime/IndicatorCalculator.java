package org.dromara.quant.runtime;

import org.dromara.quant.exchange.ExchangeGateway;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 基础 ATR 计算器。使用最近 N 根已完成 K 线的 Wilder 风格递推平均。
 */
public final class IndicatorCalculator {
    /**
     * 创建 IndicatorCalculator 实例并初始化其依赖。
     */
    private IndicatorCalculator() {
    }

    /**
     * 计算 ATR 指标。
     */
    public static BigDecimal atr(List<ExchangeGateway.Kline> klines, int period) {
        if (klines == null || period <= 0 || klines.size() < period + 1) {
            return BigDecimal.ZERO;
        }
        BigDecimal atr = null;
        for (int i = 1; i < klines.size(); i++) {
            ExchangeGateway.Kline current = klines.get(i);
            ExchangeGateway.Kline previous = klines.get(i - 1);
            BigDecimal tr = max(current.high().subtract(current.low()), current.high().subtract(previous.close()).abs(), current.low().subtract(previous.close()).abs());
            if (i < period) {
                continue;
            }
            if (atr == null) {
                atr = tr;
            } else {
                atr = atr.multiply(BigDecimal.valueOf(period - 1)).add(tr).divide(BigDecimal.valueOf(period), 16, RoundingMode.HALF_UP);
            }
        }
        return atr == null ? BigDecimal.ZERO : atr;
    }

    /**
     * 返回候选价格中的最大有效值。
     */
    private static BigDecimal max(BigDecimal a, BigDecimal b, BigDecimal c) {
        return a.max(b).max(c);
    }
}
