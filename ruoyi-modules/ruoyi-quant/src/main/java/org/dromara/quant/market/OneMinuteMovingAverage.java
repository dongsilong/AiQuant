package org.dromara.quant.market;

import org.dromara.quant.exchange.ExchangeGateway;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.List;

/**
 * 统一计算最近已收盘 1 分钟 K 线的 MA5、MA7 及方向。
 */
public final class OneMinuteMovingAverage {
    private static final int FAST_PERIOD = 5;
    private static final int SLOW_PERIOD = 7;

    private OneMinuteMovingAverage() {
    }

    public static Snapshot calculate(Collection<ExchangeGateway.Kline> klines) {
        List<BigDecimal> closes = klines.stream()
            .filter(kline -> kline != null && kline.closed() && kline.close() != null && kline.close().signum() > 0)
            .map(ExchangeGateway.Kline::close)
            .toList();
        if (closes.size() < SLOW_PERIOD) {
            return new Snapshot(null, null, null, false, closes.size());
        }
        List<BigDecimal> lastSeven = closes.subList(closes.size() - SLOW_PERIOD, closes.size());
        BigDecimal ma5 = average(lastSeven.subList(SLOW_PERIOD - FAST_PERIOD, SLOW_PERIOD));
        BigDecimal ma7 = average(lastSeven);
        return new Snapshot(ma5, ma7, trend(ma5, ma7), true, SLOW_PERIOD);
    }

    public static String trend(BigDecimal ma5, BigDecimal ma7) {
        if (ma5 == null || ma7 == null) {
            return null;
        }
        int comparison = ma5.compareTo(ma7);
        return comparison > 0 ? "UP" : comparison < 0 ? "DOWN" : "FLAT";
    }

    public static boolean directionApproved(boolean longDirection, BigDecimal ma5, BigDecimal ma7) {
        if (ma5 == null || ma7 == null) {
            return false;
        }
        int comparison = ma5.compareTo(ma7);
        return longDirection ? comparison > 0 : comparison < 0;
    }

    private static BigDecimal average(List<BigDecimal> values) {
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(values.size()), 24, RoundingMode.HALF_UP);
    }

    public record Snapshot(BigDecimal ma5, BigDecimal ma7, String trend, boolean ready, int closedKlines) {
    }
}
