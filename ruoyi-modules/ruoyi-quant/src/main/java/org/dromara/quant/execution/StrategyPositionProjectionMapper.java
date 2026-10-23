package org.dromara.quant.execution;

import org.apache.ibatis.annotations.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * InnoDB serializable per-key projection operations. Caller MUST wrap all
 * methods in the same transaction; never hold DB locks during Binance HTTP.
 */
@Mapper
public interface StrategyPositionProjectionMapper {
    /**
     * Immutable, server-owned order intent; never caller-supplied role alone.
     */

    Map<String, Object> verifiedIntent(@Param("credentialId") long credentialId, @Param("symbol") String symbol, @Param("orderId") long orderId);

    int ensure(@Param("credentialId") long credentialId, @Param("symbol") String symbol, @Param("instanceId") long instanceId, @Param("side") String side,
               @Param("strategyId") long strategyId);

    Map<String, Object> lock(@Param("credentialId") long credentialId, @Param("symbol") String symbol, @Param("instanceId") long instanceId, @Param("side") String side);

    Map<String, Object> existing(@Param("credentialId") long credentialId, @Param("symbol") String symbol, @Param("tradeId") long tradeId);

    int openCycle(@Param("id") long id, @Param("credentialId") long credentialId, @Param("symbol") String symbol, @Param("instanceId") long instanceId,
                  @Param("side") String side, @Param("cycle") long cycle, @Param("openedAt") LocalDateTime openedAt);

    int closeCycle(@Param("cycleId") long cycleId, @Param("closedAt") LocalDateTime closedAt);

    int insertFill(@Param("id") long id, @Param("credentialId") long credentialId, @Param("symbol") String symbol, @Param("strategyId") long strategyId,
                   @Param("instanceId") long instanceId, @Param("cycleId") long cycleId, @Param("tradeId") long tradeId, @Param("orderId") long orderId,
                   @Param("side") String side, @Param("role") String role, @Param("exchangeSide") String exchangeSide, @Param("quantity") BigDecimal quantity,
                   @Param("price") BigDecimal price, @Param("fee") BigDecimal fee, @Param("feeAsset") String feeAsset, @Param("pnl") BigDecimal pnl,
                   @Param("tradeTime") LocalDateTime tradeTime);

    int update(@Param("credentialId") long credentialId, @Param("symbol") String symbol, @Param("instanceId") long instanceId, @Param("side") String side,
               @Param("strategyId") long strategyId, @Param("quantity") BigDecimal quantity, @Param("cost") BigDecimal cost, @Param("pnl") BigDecimal pnl,
               @Param("fees") BigDecimal fees, @Param("feeAsset") String feeAsset, @Param("cycle") long cycle, @Param("cycleId") Long cycleId,
               @Param("tradeTime") LocalDateTime tradeTime, @Param("tradeId") long tradeId);
}
