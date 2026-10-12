package org.dromara.quant.strategy.domain;

import java.util.List;

/**
 * 策略账户批量绑定请求。
 */
public record StrategyAccountBatchRequest(List<Long> credentialIds) {
}
