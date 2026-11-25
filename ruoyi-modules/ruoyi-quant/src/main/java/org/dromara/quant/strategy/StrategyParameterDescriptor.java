package org.dromara.quant.strategy;

import java.math.BigDecimal;

/**
 * 策略参数的表单元数据。当前仅描述数值参数，不参与策略参数校验或运行时计算。
 *
 * @param key 参数键
 * @param label 表单标签
 * @param defaultValue 默认值
 * @param min 最小值
 * @param max 最大值
 * @param step 输入步长
 * @param rate 是否为比例参数
 */
public record StrategyParameterDescriptor(String key, String label, BigDecimal defaultValue, BigDecimal min,
                                          BigDecimal max, BigDecimal step, boolean rate) {
}
