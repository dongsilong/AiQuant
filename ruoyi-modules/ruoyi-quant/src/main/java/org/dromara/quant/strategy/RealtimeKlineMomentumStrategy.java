package org.dromara.quant.strategy;

import org.dromara.config.strategy.*;
import org.dromara.config.strategy.context.StrategyContext;
import org.dromara.config.strategy.metadata.StrategyDescriptor;
import org.dromara.config.strategy.signal.EntrySignal;
import org.dromara.config.strategy.signal.ExitSignal;
import org.dromara.config.strategy.signal.ProtectionSignal;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 简单实时 K 线动量示例策略。
 *
 * <p>入场：以“上一根已完成 K 线收盘价”为基准，当前实时 K 线价格向上/向下超过阈值时开多/开空。
 * <p>平仓：当前价格相对开仓价达到止盈或止损阈值时全部平仓。
 * <p>该策略只计算信号，不直接调用交易所。
 */
@Component
@LegacyStrategy
public final class RealtimeKlineMomentumStrategy implements StrategyPlugin {
    /**
     * 策略插件唯一编码。
     */
    public static final String CODE = "realtime_kline_momentum";
    /**
     * 策略插件版本。
     */
    private static final String VERSION = "1.0";
    /**
     * 示例动量策略默认开仓涨跌幅阈值。
     */
    private static final BigDecimal DEFAULT_ENTRY_CHANGE_RATIO = new BigDecimal("0.002");
    /**
     * 示例动量策略默认止盈比例。
     */
    private static final BigDecimal DEFAULT_TAKE_PROFIT_RATIO = new BigDecimal("0.005");
    /**
     * 示例动量策略默认止损比例。
     */
    private static final BigDecimal DEFAULT_STOP_LOSS_RATIO = new BigDecimal("0.003");

    /**
     * 按主参数名、兼容别名和默认值读取 BigDecimal 参数。
     */
    private static BigDecimal decimal(Map<String, Object> params, String key, BigDecimal defaultValue) {
        if (params == null || params.get(key) == null) {
            return defaultValue;
        }
        try {
            BigDecimal value = new BigDecimal(String.valueOf(params.get(key)));
            if (value.signum() <= 0) {
                throw new IllegalArgumentException("策略参数必须大于零: " + key);
            }
            return value;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("策略参数无效: " + key, ex);
        }
    }

    /**
     * 读取必需数据，不满足条件时抛出异常。
     */
    private static void requirePositive(Map<String, Object> params, String key, BigDecimal defaultValue) {
        decimal(params, key, defaultValue);
    }

    /**
     * 返回当前策略插件的编码、版本和展示名称。
     */
    @Override
    public StrategyDescriptor descriptor() {
        return new StrategyDescriptor(CODE, VERSION, "实时K线动量示例");
    }

    /**
     * 校验策略参数是否合法；不合法时抛出参数异常。
     */
    @Override
    public void validateConfig(Map<String, Object> params) {
        requirePositive(params, "entryChangeRatio", DEFAULT_ENTRY_CHANGE_RATIO);
        requirePositive(params, "takeProfitRatio", DEFAULT_TAKE_PROFIT_RATIO);
        requirePositive(params, "stopLossRatio", DEFAULT_STOP_LOSS_RATIO);
    }

    /**
     * 根据当前市场上下文计算是否产生开仓信号。
     */
    @Override
    public Optional<EntrySignal> evaluateEntry(StrategyContext context) {
        return evaluateEntry(context, Map.of());
    }

    /**
     * 根据当前市场上下文计算是否产生开仓信号。
     */
    @Override
    public Optional<EntrySignal> evaluateEntry(StrategyContext context, Map<String, Object> params) {
        if (context == null || context.currentPrice() == null || context.referencePrice() == null) {
            return Optional.empty();
        }
        if (context.currentPrice().signum() <= 0 || context.referencePrice().signum() <= 0) {
            return Optional.empty();
        }
        BigDecimal threshold = decimal(params, "entryChangeRatio", DEFAULT_ENTRY_CHANGE_RATIO);
        BigDecimal change = context.currentPrice().subtract(context.referencePrice()).divide(context.referencePrice(), 12, RoundingMode.HALF_UP);
        if (change.compareTo(threshold) >= 0) {
            return Optional.of(new EntrySignal(EntrySignal.Direction.LONG, "实时价格较上一根已完成K线收盘价上涨达到 " + threshold));
        }
        if (change.compareTo(threshold.negate()) <= 0) {
            return Optional.of(new EntrySignal(EntrySignal.Direction.SHORT, "实时价格较上一根已完成K线收盘价下跌达到 " + threshold));
        }
        return Optional.empty();
    }

    /**
     * 根据当前持仓和实时价格计算退出信号。
     */
    @Override
    public List<ExitSignal> evaluateExit(StrategyContext context) {
        return evaluateExit(context, Map.of());
    }

    /**
     * 根据当前持仓和实时价格计算退出信号。
     */
    @Override
    public List<ExitSignal> evaluateExit(StrategyContext context, Map<String, Object> params) {
        if (context == null || context.entryPrice() == null || context.currentPrice() == null || context.quantity() == null || context.entryPrice().signum() <= 0 || context.quantity().signum() <= 0) {
            return List.of();
        }
        BigDecimal takeProfit = decimal(params, "takeProfitRatio", DEFAULT_TAKE_PROFIT_RATIO);
        BigDecimal stopLoss = decimal(params, "stopLossRatio", DEFAULT_STOP_LOSS_RATIO);
        BigDecimal change = context.currentPrice().subtract(context.entryPrice()).divide(context.entryPrice(), 12, RoundingMode.HALF_UP);
        if ("SHORT".equalsIgnoreCase(context.direction())) {
            change = change.negate();
        }
        if (change.compareTo(stopLoss.negate()) <= 0) {
            return List.of(new ExitSignal("STOP_LOSS", BigDecimal.ONE, "实时价格达到止损阈值", false, "LOCAL_STOP"));
        }
        if (change.compareTo(takeProfit) >= 0) {
            return List.of(new ExitSignal("TAKE_PROFIT", BigDecimal.ONE, "实时价格达到止盈阈值", false, "FIRST_TP"));
        }
        return List.of();
    }
    /** 与本地退出使用相同价格涨跌幅，保留原策略语义。 */
    @Override
    public List<ProtectionSignal> evaluateProtection(StrategyContext context, Map<String, Object> params) {
        if (context == null || context.entryPrice() == null || context.entryPrice().signum() <= 0 || context.quantity() == null || context.quantity().signum() <= 0) {
            return List.of();
        }
        BigDecimal takeProfit = decimal(params, "takeProfitRatio", DEFAULT_TAKE_PROFIT_RATIO);
        BigDecimal stopLoss = decimal(params, "stopLossRatio", DEFAULT_STOP_LOSS_RATIO);
        boolean shortPosition = "SHORT".equalsIgnoreCase(context.direction());
        BigDecimal stop = context.entryPrice().multiply(shortPosition ? BigDecimal.ONE.add(stopLoss) : BigDecimal.ONE.subtract(stopLoss));
        BigDecimal profit = context.entryPrice().multiply(shortPosition ? BigDecimal.ONE.subtract(takeProfit) : BigDecimal.ONE.add(takeProfit));
        return List.of(new ProtectionSignal("LOCAL_STOP", "STOP_MARKET", BigDecimal.ONE, ProtectionSignal.QuantityBase.CURRENT, stop, "Binance 动量止损镜像"),
            new ProtectionSignal("FIRST_TP", "TAKE_PROFIT_MARKET", BigDecimal.ONE, ProtectionSignal.QuantityBase.CURRENT, profit, "Binance 动量止盈镜像"));
    }
}
