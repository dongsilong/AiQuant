package org.dromara.config.strategy.metadata;

import java.util.Map;

/**
 * 插件参数的 UI schema 描述，由插件随版本提供。
 */
public record StrategyParamSchema(String key, String label, String type, boolean required, Object defaultValue, Map<String, Object> constraints, String description) {
    public StrategyParamSchema {
        constraints = constraints == null ? Map.of() : Map.copyOf(constraints);
    }
}
