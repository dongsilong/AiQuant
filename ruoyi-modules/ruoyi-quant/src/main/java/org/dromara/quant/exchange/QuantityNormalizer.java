package org.dromara.quant.exchange;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 按交易所 PRICE_FILTER/LOT_SIZE 归一化，缺少正过滤器时直接拒绝。
 */
public final class QuantityNormalizer {
    /**
     * 创建 QuantityNormalizer 实例并初始化其依赖。
     */
    private QuantityNormalizer() {
    }

    /**
     * 根据交易规则规范化价格。
     */
    public static BigDecimal price(BigDecimal value, ExchangeGateway.SymbolRule rule) {
        if (value == null || rule == null || rule.priceTickSize() == null || rule.priceTickSize().signum() <= 0) {
            throw new IllegalArgumentException("交易对缺少有效价格精度");
        }
        return floor(value, rule.priceTickSize());
    }

    /**
     * 根据交易规则规范化下单数量。
     */
    public static BigDecimal quantity(BigDecimal value, ExchangeGateway.SymbolRule rule, String orderType) {
        if (rule == null || !"MARKET".equalsIgnoreCase(orderType)) {
            return quantity(value, rule);
        }
        BigDecimal lot = rule.stepSize();
        BigDecimal market = rule.marketStepSize();
        if (lot == null || lot.signum() <= 0 || market == null || market.signum() <= 0) {
            throw new IllegalArgumentException("交易对缺少有效市价数量步长");
        }
        int scale = Math.max(0, Math.max(lot.scale(), market.scale()));
        var a = lot.movePointRight(scale).toBigIntegerExact();
        var b = market.movePointRight(scale).toBigIntegerExact();
        BigDecimal step = new BigDecimal(a.divide(a.gcd(b)).multiply(b), scale);
        return quantity(value, new ExchangeGateway.SymbolRule(rule.symbol(), rule.quoteAsset(), rule.pricePrecision(), rule.quantityPrecision(), rule.priceTickSize(), step,
            market, rule.minNotional(), rule.status()));
    }

    public static BigDecimal quantity(BigDecimal value, ExchangeGateway.SymbolRule rule) {
        if (value == null || rule == null || rule.stepSize() == null || rule.stepSize().signum() <= 0) {
            throw new IllegalArgumentException("交易对缺少有效数量步长");
        }
        BigDecimal normalized = floor(value, rule.stepSize());
        if (normalized.signum() <= 0 || rule.minNotional() == null || rule.minNotional().signum() <= 0) {
            throw new IllegalArgumentException("交易对缺少有效最小名义价值");
        }
        return normalized;
    }

    /**
     * 校验订单名义价值是否满足最小要求。
     */
    public static void requireNotional(BigDecimal price, BigDecimal quantity, ExchangeGateway.SymbolRule rule) {
        if (price == null || quantity == null || rule == null || rule.minNotional() == null || price.multiply(quantity).compareTo(rule.minNotional()) < 0) {
            throw new IllegalArgumentException("订单名义价值低于交易所最小限制");
        }
    }

    /**
     * 按指定步进向下取整。
     */
    private static BigDecimal floor(BigDecimal value, BigDecimal step) {
        return value.divide(step, 0, RoundingMode.DOWN).multiply(step).stripTrailingZeros();
    }
}
