package org.dromara.quant.runtime;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 策略 Runtime 实际使用的斜率和 1 分钟均线快照。
 */
public record StrategySlopeTelemetry(
    Long strategyId,
    Long instanceId,
    Long credentialId,
    String symbol,
    String interval,
    BigDecimal currentPrice,
    BigDecimal referenceOpenPrice,
    BigDecimal slopePercent,
    BigDecimal degrees,
    BigDecimal entryThreshold,
    boolean entryApproved,
    Instant eventTime,
    Instant receivedAt,
    long sequence,
    boolean slopeReady,
    BigDecimal ma5,
    BigDecimal ma7,
    String maTrend,
    boolean maReady,
    String maBlockReason,
    Instant maLastClosedKlineOpenTime,
    Instant maEventTime,
    Instant maReceivedAt,
    long slopeFreshForMillis,
    long maFreshForMillis,
    Instant publishedAt
) {
}
