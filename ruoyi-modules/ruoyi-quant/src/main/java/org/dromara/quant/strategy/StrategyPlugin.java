package org.dromara.quant.strategy;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 策略插件边界：只负责信号计算，不负责账户、订单、数据库和交易所调用。
 */
public interface StrategyPlugin {
    /**
     * 返回当前策略插件的编码、版本和展示名称。
     */
    StrategyDescriptor descriptor();

    /**
     * 校验策略参数是否合法；不合法时抛出参数异常。
     */
    void validateConfig(Map<String, Object> params);

    /**
     * 根据当前市场上下文计算是否产生开仓信号。
     */
    Optional<EntrySignal> evaluateEntry(StrategyContext context);

    /**
     * 根据当前持仓和实时价格计算退出信号。
     */
    List<ExitSignal> evaluateExit(StrategyContext context);

    /**
     * 交易所侧保护意图；默认无保护单，由 Runtime 统一同步。
     */
    default List<ProtectionSignal> evaluateProtection(StrategyContext context, Map<String, Object> params) {
        return List.of();
    }

    /**
     * 根据当前市场上下文计算是否产生开仓信号。
     */
    default Optional<EntrySignal> evaluateEntry(StrategyContext context, Map<String, Object> params) {
        return evaluateEntry(context);
    }

    /**
     * 根据当前持仓和实时价格计算退出信号。
     */
    default List<ExitSignal> evaluateExit(StrategyContext context, Map<String, Object> params) {
        return evaluateExit(context);
    }
}
