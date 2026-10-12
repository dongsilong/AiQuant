package org.dromara.quant.strategy.domain;

import java.util.List;
import java.util.Map;

/**
 * 策略启动前的服务端配置预检结果。
 */
public record StrategyPreflightResult(boolean valid, int instanceCount, List<Map<String, Object>> accounts, List<Map<String, Object>> symbols, List<String> errors,
                                      List<String> warnings) {
}
