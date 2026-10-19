package org.dromara.quant.runtime;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 已成功持久化的策略持仓峰值快照，用于终端实时展示。 */
public record StrategyPositionPerformanceEvent(Long credentialId, Long instanceId, Long positionId, String positionCycleKey, String symbol, String direction,
                                               BigDecimal entryPrice, BigDecimal initialPositionQty, BigDecimal initialMargin, BigDecimal highestPriceSinceEntry,
                                               BigDecimal lowestPriceSinceEntry, LocalDateTime updatedAt) {
}
