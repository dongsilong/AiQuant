package org.dromara.quant.decision;

import lombok.Builder;
import java.math.BigDecimal;
import java.time.Instant;

/** 不包含凭证密钥的不可变日志快照。 */
@Builder(toBuilder = true)
public record DecisionLogEvent(String eventKey, String recordType, Long credentialId, String accountName, String runMode,
    Long strategyId, Long strategyInstanceId, String strategyName, String symbol, String operation, String direction,
    String status, String reasonCode, String reason, BigDecimal angle, BigDecimal threshold, Instant klineTime,
    BigDecimal quantity, BigDecimal price, BigDecimal margin, String idempotencyKey, Long orderId, String clientOrderId,
    Instant eventTime, Instant tradeTime, Long tradeId, String orderStatus, Boolean reduceOnly, String rawEvent, BigDecimal cumulativeQuantity, BigDecimal averagePrice) {
}
