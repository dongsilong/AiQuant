package org.dromara.quant.runtime;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 双开策略固定初始保证金 ROI 止损触发价计算。
 *
 * <p>netAdjustment 是已累计的净账务调整：已实现盈亏 + 资金费收入 - 手续费等成本。
 * 调用方必须按现有账务口径汇总；本类不猜测或重复扣减费用。</p>
 */
public final class DualHedgeProtectionPriceCalculator {
    private static final int PRICE_SCALE = 16;
    private static final BigDecimal ONE = BigDecimal.ONE;

    private DualHedgeProtectionPriceCalculator() {
    }

    /**
     * 计算达到目标净 ROI 时的 MARK_PRICE 触发价。
     *
     * @param direction LONG 或 SHORT
     * @param entryPrice 实际开仓均价
     * @param remainingQuantity 当前剩余持仓数量（正负均可）
     * @param initialMargin 开仓时冻结的初始保证金，不随剩余数量变化
     * @param netAdjustment 已累计净账务调整，正数为收益、负数为成本
     * @param targetRoi 目标 ROI 小数比例，例如 -0.03 表示 -3%
     */
    public static BigDecimal stopPrice(String direction, BigDecimal entryPrice, BigDecimal remainingQuantity,
                                       BigDecimal initialMargin, BigDecimal netAdjustment, BigDecimal targetRoi) {
        if (!"LONG".equalsIgnoreCase(direction) && !"SHORT".equalsIgnoreCase(direction)) {
            throw new IllegalArgumentException("双开止损方向必须为 LONG 或 SHORT");
        }
        if (entryPrice == null || entryPrice.signum() <= 0
            || remainingQuantity == null || remainingQuantity.signum() == 0
            || initialMargin == null || initialMargin.signum() <= 0
            || netAdjustment == null || targetRoi == null || targetRoi.signum() >= 0
            || targetRoi.compareTo(ONE.negate()) <= 0) {
            throw new IllegalArgumentException("双开止损价格计算参数无效");
        }

        // 目标净盈亏 = 固定初始保证金 * 目标 ROI。
        // 价格盈亏还需扣除已累计净账务调整。
        BigDecimal requiredPricePnl = initialMargin.multiply(targetRoi).subtract(netAdjustment);
        BigDecimal priceMove = requiredPricePnl.divide(remainingQuantity.abs(), PRICE_SCALE, RoundingMode.HALF_UP);
        BigDecimal triggerPrice = "LONG".equalsIgnoreCase(direction)
            ? entryPrice.add(priceMove) : entryPrice.subtract(priceMove);
        if (triggerPrice.signum() <= 0) {
            throw new IllegalArgumentException("双开止损目标对应的触发价格无效");
        }
        return triggerPrice.setScale(PRICE_SCALE, RoundingMode.HALF_UP).stripTrailingZeros();
    }
}
