package org.dromara.quant.strategy.domain;

import java.util.List;

/**
 * 策略交易对批量配置请求。
 */
public record StrategySymbolBatchRequest(List<QuantStrategySymbol> items) {
}
