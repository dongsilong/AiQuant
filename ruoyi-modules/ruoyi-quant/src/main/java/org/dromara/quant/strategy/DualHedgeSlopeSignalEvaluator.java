package org.dromara.quant.strategy;

import org.dromara.quant.exchange.ExchangeGateway;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * 双开对敲策略的 15 分钟已收盘 K 线入场信号计算。
 *
 * <p>该计算器只负责判断是否触发双向开仓，不生成单方向 EntrySignal，
 * 也不参与既有单持仓策略的插件执行路径。
 */
public final class DualHedgeSlopeSignalEvaluator {
    public static final String CODE = "dual_hedge_slope";
    public static final BigDecimal DEFAULT_ENTRY_ANGLE_DEGREES = new BigDecimal("45");
    public static final BigDecimal DEFAULT_MARGIN_RATIO = new BigDecimal("0.10");

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private DualHedgeSlopeSignalEvaluator() {
    }

    /**
     * 只有 15m 已收盘 K 线参与决策；角度正负均触发 LONG + SHORT。
     *
     * @param interval 行情周期
     * @param kline 已收盘 K 线
     * @param referenceOpenPrice 与既有斜率算法一致的回看参考开盘价
     * @param params 合并后的策略参数
     */
    public static Optional<Signal> evaluate(String interval, ExchangeGateway.Kline kline,
                                            BigDecimal referenceOpenPrice, Map<String, Object> params) {
        if (!"15m".equalsIgnoreCase(interval) || kline == null || !kline.closed()
            || kline.openTime() == null || kline.close() == null || kline.close().signum() <= 0
            || referenceOpenPrice == null || referenceOpenPrice.signum() <= 0) {
            return Optional.empty();
        }

        BigDecimal threshold = entryAngleDegrees(params);
        BigDecimal slopePercent = kline.close().subtract(referenceOpenPrice)
            .multiply(HUNDRED).divide(referenceOpenPrice, 16, RoundingMode.HALF_EVEN);
        double rawDegrees = Math.toDegrees(Math.atan(slopePercent.doubleValue()));
        BigDecimal degrees = BigDecimal.valueOf(rawDegrees).setScale(2, RoundingMode.HALF_UP);
        if (BigDecimal.valueOf(Math.abs(rawDegrees)).compareTo(threshold) <= 0) {
            return Optional.empty();
        }

        return Optional.of(new Signal(kline.openTime(), referenceOpenPrice, kline.close(), slopePercent,
            degrees, threshold, true, true));
    }

    public static BigDecimal entryAngleDegrees(Map<String, Object> params) {
        BigDecimal value = decimal(params, "entryAngleDegrees", DEFAULT_ENTRY_ANGLE_DEGREES);
        if (value.signum() <= 0 || value.compareTo(BigDecimal.valueOf(90)) >= 0) {
            throw new IllegalArgumentException("entryAngleDegrees 必须 > 0 且 < 90");
        }
        return value;
    }

    public static BigDecimal marginRatio(Map<String, Object> params) {
        BigDecimal value = decimal(params, "marginRatio", DEFAULT_MARGIN_RATIO);
        if (value.signum() <= 0 || value.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException("marginRatio 必须 > 0 且 < 1");
        }
        return value;
    }

    private static BigDecimal decimal(Map<String, Object> params, String key, BigDecimal fallback) {
        Object raw = params == null ? null : params.get(key);
        if (raw == null) {
            return fallback;
        }
        try {
            return new BigDecimal(String.valueOf(raw));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("策略参数无效: " + key, ex);
        }
    }

    public record Signal(Instant klineOpenTime, BigDecimal referenceOpenPrice, BigDecimal closePrice,
                         BigDecimal slopePercent, BigDecimal angleDegrees, BigDecimal entryThresholdDegrees,
                         boolean openLong, boolean openShort) {
    }
}
