package org.dromara.config.strategy;

import org.dromara.config.strategy.metadata.StrategyDescriptor;
import org.dromara.config.strategy.metadata.StrategyParamSchema;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 策略插件注册表；历史插件仅可供存量持仓保护路径读取。
 */
@Component
public class StrategyPluginRegistry {
    private final Map<String, StrategyPlugin> plugins;
    private final Map<String, StrategyPlugin> legacyPlugins;

    public StrategyPluginRegistry(List<StrategyPlugin> plugins) {
        this.plugins =
            plugins.stream().filter(plugin -> !plugin.getClass().isAnnotationPresent(LegacyStrategy.class)).collect(Collectors.toUnmodifiableMap(plugin -> plugin.descriptor().code(), Function.identity()));
        this.legacyPlugins =
            plugins.stream().filter(plugin -> plugin.getClass().isAnnotationPresent(LegacyStrategy.class)).collect(Collectors.toUnmodifiableMap(plugin -> plugin.descriptor().code(), Function.identity()));
    }

    private static void validateField(StrategyParamSchema field, Object value) {
        String type = field.type() == null ? "" : field.type().toLowerCase(java.util.Locale.ROOT);
        switch (type) {
            case "number", "integer" -> {
                if (!(value instanceof Number)) {
                    throw new IllegalArgumentException("参数类型错误: " + field.key());
                }
                BigDecimal number = new BigDecimal(value.toString());
                if ("integer".equals(type) && number.stripTrailingZeros().scale() > 0) {
                    throw new IllegalArgumentException("参数必须为整数: " + field.key());
                }
                compareBound(field, number, "min", true);
                compareBound(field, number, "max", false);
            }
            case "string" -> {
                if (!(value instanceof String)) {
                    throw new IllegalArgumentException("参数类型错误: " + field.key());
                }
            }
            case "boolean" -> {
                if (!(value instanceof Boolean)) {
                    throw new IllegalArgumentException("参数类型错误: " + field.key());
                }
            }
            case "enum" -> {
                Object options = field.constraints().get("options");
                if (!(options instanceof List<?> list) || list.stream().noneMatch(option -> option instanceof Map<?, ?> item && java.util.Objects.equals(item.get("value"),
                    value))) {
                    throw new IllegalArgumentException("参数值不在允许范围内: " + field.key());
                }
            }
            default -> throw new IllegalArgumentException("不支持的参数 schema 类型: " + field.key());
        }
    }

    private static void compareBound(StrategyParamSchema field, BigDecimal value, String key, boolean lower) {
        Object bound = field.constraints().get(key);
        if (bound == null) {
            return;
        }
        BigDecimal limit = new BigDecimal(bound.toString());
        if (lower ? value.compareTo(limit) < 0 : value.compareTo(limit) > 0) {
            throw new IllegalArgumentException("参数超出允许范围: " + field.key());
        }
    }

    public StrategyPlugin require(String code) {
        StrategyPlugin plugin = plugins.get(code);
        if (plugin == null) {
            throw new IllegalArgumentException("未注册策略插件: " + code);
        }
        return plugin;
    }

    public Optional<StrategyPlugin> find(String code) {
        return Optional.ofNullable(plugins.get(code));
    }

    public void validate(String code, Map<String, Object> params) {
        validate(require(code), params);
    }

    public void validate(StrategyPlugin plugin, Map<String, Object> params) {
        Map<String, Object> values = new LinkedHashMap<>(params == null ? Map.of() : params);
        List<StrategyParamSchema> schema = plugin.descriptor().paramsSchema();
        boolean legacy = plugin.getClass().isAnnotationPresent(LegacyStrategy.class);
        if (schema.isEmpty() && !legacy) {
            throw new IllegalArgumentException("策略插件必须定义参数 schema");
        }
        for (StrategyParamSchema field : schema) {
            Object value = values.get(field.key());
            if (value == null && field.defaultValue() != null) {
                value = field.defaultValue();
                values.put(field.key(), value);
            }
            if (value == null) {
                if (field.required()) {
                    throw new IllegalArgumentException("缺少必填参数: " + field.key());
                }
                continue;
            }
            validateField(field, value);
        }
        if (!legacy) {
            for (String key : values.keySet()) {
                if (schema.stream().noneMatch(field -> field.key().equals(key))) {
                    throw new IllegalArgumentException("未定义的策略参数: " + key);
                }
            }
        }
        plugin.validateConfig(values);
    }

    /**
     * 只允许运行时在确认已有未平仓状态后查询，调用方不得用于创建或开仓。
     */
    public Optional<StrategyPlugin> findForProtection(String code) {
        return Optional.ofNullable(legacyPlugins.get(code));
    }

    public List<StrategyDescriptor> descriptors() {
        return plugins.values().stream().map(StrategyPlugin::descriptor).sorted(Comparator.comparing(StrategyDescriptor::code)).toList();
    }

    public List<String> pluginCodes() {
        return plugins.keySet().stream().sorted().toList();
    }
}
