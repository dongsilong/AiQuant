package org.dromara.quant.strategy;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 策略插件注册表，根据插件编码定位 StrategyPlugin，并提供插件元数据。
 */
@Component
public class StrategyPluginRegistry {
    /**
     * 按插件编码保存的 StrategyPlugin 集合。
     */
    private final Map<String, StrategyPlugin> plugins;

    /**
     * 创建 StrategyPluginRegistry 实例并初始化其依赖。
     */
    public StrategyPluginRegistry(List<StrategyPlugin> plugins) {
        this.plugins = plugins.stream().collect(Collectors.toUnmodifiableMap(plugin -> plugin.descriptor().code(), Function.identity()));
    }

    /**
     * 读取必需数据，不满足条件时抛出异常。
     */
    public StrategyPlugin require(String code) {
        StrategyPlugin plugin = plugins.get(code);
        if (plugin == null) {
            throw new IllegalArgumentException("未注册策略插件: " + code);
        }
        return plugin;
    }

    /**
     * 返回当前已注册策略插件的描述信息。
     */
    public List<StrategyDescriptor> descriptors() {
        return plugins.values().stream().map(StrategyPlugin::descriptor).sorted(java.util.Comparator.comparing(StrategyDescriptor::code)).toList();
    }
}
