package org.dromara.config.strategy.metadata;

import java.util.List;
import java.util.Map;

/**
 * 策略插件描述信息，用于标识插件编码、版本、展示名称和参数表单 schema。
 */
public record StrategyDescriptor(String code, String version, String name, Map<String, Object> paramsExample, List<StrategyParamSchema> paramsSchema) {
    public StrategyDescriptor {
        paramsExample = paramsExample == null ? Map.of() : Map.copyOf(paramsExample);
        paramsSchema = paramsSchema == null ? List.of() : List.copyOf(paramsSchema);
    }

    public StrategyDescriptor(String code, String version, String name, Map<String, Object> paramsExample) {
        this(code, version, name, paramsExample, List.of());
    }

    public StrategyDescriptor(String code, String version, String name) {
        this(code, version, name, Map.of(), List.of());
    }
}
