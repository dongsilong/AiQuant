package org.dromara.quant.execution.mapper;

import org.apache.ibatis.annotations.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 读写成交归属及策略持仓投影，并以事务约束防止重复分配。
 */
@Mapper
public interface StrategyFillAllocationMapper {

    Map<String, Object> getAllocation(@Param("credentialId") Long credentialId, @Param("symbol") String symbol, @Param("tradeId") Long tradeId);

    int insertAllocation(@Param("credentialId") Long credentialId, @Param("symbol") String symbol, @Param("tradeId") Long tradeId, @Param("orderId") Long orderId, @Param(
        "strategyId") Long strategyId, @Param("instanceId") Long instanceId, @Param("side") String side, @Param("quantity") BigDecimal quantity, @Param("price") BigDecimal price
        , @Param("fee") BigDecimal fee, @Param("feeAsset") String feeAsset, @Param("pnl") BigDecimal pnl, @Param("tradeTime") LocalDateTime tradeTime);

    /**
     * AUDIT ONLY: lifetime executed BUY/SELL quantities are not signed open position
     * quantity. Net open interest cannot be inferred from a one-way BOTH account
     * without immutable open/close role and position-cycle attribution.
     */

    Map<String, Object> summarizeVerifiedFills(@Param("credentialId") Long credentialId, @Param("strategyId") Long strategyId, @Param("instanceId") Long instanceId, @Param(
        "symbol") String symbol);

}
