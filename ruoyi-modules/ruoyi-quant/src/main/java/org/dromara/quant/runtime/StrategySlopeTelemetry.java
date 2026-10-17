package org.dromara.quant.runtime;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 策略实际计算出的斜率快照，只用于实时展示和审计，不包含交易执行动作。
 */
public record StrategySlopeTelemetry(Long strategyId, Long instanceId, Long credentialId, String symbol, String interval, BigDecimal currentPrice, BigDecimal referenceOpenPrice,
                                     BigDecimal slopePercent, BigDecimal degrees, BigDecimal entryThreshold, boolean entryApproved, Instant eventTime) {
}
