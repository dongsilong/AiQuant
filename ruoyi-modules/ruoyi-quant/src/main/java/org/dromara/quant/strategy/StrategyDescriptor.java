package org.dromara.quant.strategy;

import java.util.Map;

/**
 * 策略插件描述信息，用于标识插件编码、版本和展示名称。
 *
 * @param code    策略插件唯一编码
 * @param version 乐观锁版本号
 * @param name    策略插件展示名称
 */
public record StrategyDescriptor(String code, String version, String name, Map<String, Object> paramsExample) {
    public StrategyDescriptor(String code, String version, String name) {
        this(code, version, name, Map.of());
    }
}
