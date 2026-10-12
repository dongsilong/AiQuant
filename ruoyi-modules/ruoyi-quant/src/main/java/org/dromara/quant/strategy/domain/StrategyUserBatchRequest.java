package org.dromara.quant.strategy.domain;

import java.util.List;

/** 策略登录用户批量授权请求。 */
public record StrategyUserBatchRequest(List<Long> userIds) {
}
