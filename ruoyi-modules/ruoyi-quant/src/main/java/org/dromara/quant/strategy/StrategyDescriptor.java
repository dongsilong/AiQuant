package org.dromara.quant.strategy;

import java.util.List;
import java.util.Map;

/**
 * 策略插件描述信息，用于标识插件编码、版本和展示名称。
 *
 * @param code 策略插件唯一编码
 * @param version 乐观锁版本号
 * @param name 策略插件展示名称
 * @param paramsExample 策略参数示例
 * @param parameters 策略参数表单定义；为空时由前端使用兼容表单
 */
public record StrategyDescriptor(String code, String version, String name, Map<String, Object> paramsExample,
                                 List<StrategyParameterDescriptor> parameters) {
    public StrategyDescriptor {
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
    }

    public StrategyDescriptor(String code, String version, String name) {
        this(code, version, name, Map.of(), List.of());
    }

    public StrategyDescriptor(String code, String version, String name, Map<String, Object> paramsExample) {
        this(code, version, name, paramsExample, List.of());
    }
}
