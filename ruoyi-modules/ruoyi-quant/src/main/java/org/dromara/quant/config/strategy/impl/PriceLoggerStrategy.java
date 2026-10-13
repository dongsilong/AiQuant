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

/** 只读演示插件，不执行交易。实时行情由策略管理服务订阅。 */
@Component
public class PriceLoggerStrategy implements StrategyPlugin {
    @Override
    public StrategyDescriptor descriptor() {
        return new StrategyDescriptor("price_logger", "1.0", "ETHUSDT 15m 实时价格日志",
            Map.of(), List.of(new StrategyParamSchema("enabled", "启用", "boolean",
                false, true, Map.of(), "演示策略，无交易行为")));
    }
    @Override
    public void validateConfig(Map<String, Object> params) { }
    @Override
    public Optional<EntrySignal> evaluateEntry(StrategyContext context) {
        return Optional.empty();
    }
    @Override
    public List<ExitSignal> evaluateExit(StrategyContext context) {
        return List.of();
    }
}
