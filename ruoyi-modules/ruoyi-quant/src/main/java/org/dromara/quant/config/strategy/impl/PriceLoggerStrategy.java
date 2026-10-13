package org.dromara.quant.config.strategy.impl;

import org.dromara.quant.config.strategy.StrategyPlugin;
import org.dromara.quant.config.strategy.context.StrategyContext;
import org.dromara.quant.config.strategy.metadata.StrategyDescriptor;
import org.dromara.quant.config.strategy.metadata.StrategyParamSchema;
import org.dromara.quant.config.strategy.signal.EntrySignal;
import org.dromara.quant.config.strategy.signal.ExitSignal;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 只读行情演示插件。策略运行服务负责订阅 ETHUSDT 15m 并记录日志。
 * 不产生任何开仓、平仓或保护单信号。
 */
@Component
public final class PriceLoggerStrategy implements StrategyPlugin {
    public static final String CODE = "price_logger";

    @Override
    public StrategyDescriptor descriptor() {
        return new StrategyDescriptor(CODE, "1.0", "实时价格日志（ETHUSDT / 15m）",
            Map.of(), List.of(new StrategyParamSchema("enabled", "启用日志", "boolean",
                false, true, Map.of(), "演示参数；此版本始终记录行情日志")));
    }

    @Override
    public void validateConfig(Map<String, Object> params) {
        // 此演示插件无交易参数。
    }

    @Override
    public Optional<EntrySignal> evaluateEntry(StrategyContext context) {
        return Optional.empty();
    }

    @Override
    public List<ExitSignal> evaluateExit(StrategyContext context) {
        return List.of();
    }
}
