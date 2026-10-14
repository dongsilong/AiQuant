package org.dromara.quant.execution.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

@Mapper
public interface StrategyFillAllocationMapper {
    @Options(timeout = 3)
    @Select("""
        SELECT strategy_id, strategy_instance_id, exchange_order_id,
               allocated_qty, allocated_price, allocated_fee, allocated_fee_asset, realized_pnl, side, trade_time
        FROM quant_strategy_fill_allocation
        WHERE credential_id=#{credentialId} AND symbol=#{symbol}
          AND exchange_trade_id=#{tradeId}
        """)
    Map<String, Object> getAllocation(@Param("credentialId") Long credentialId,
                                      @Param("symbol") String symbol, @Param("tradeId") Long tradeId);

    @Options(timeout = 3)
    @Insert("""
        INSERT INTO quant_strategy_fill_allocation
          (credential_id,symbol,exchange_trade_id,exchange_order_id,strategy_id,
           strategy_instance_id,side,allocated_qty,allocated_price,allocated_fee,
           allocated_fee_asset,realized_pnl,trade_time,created_at)
        VALUES (#{credentialId},#{symbol},#{tradeId},#{orderId},#{strategyId},
                #{instanceId},#{side},#{quantity},#{price},#{fee},#{feeAsset},#{pnl},
                #{tradeTime},NOW())
        """)
    int insertAllocation(@Param("credentialId") Long credentialId,
                         @Param("symbol") String symbol,
                         @Param("tradeId") Long tradeId,
                         @Param("orderId") Long orderId,
                         @Param("strategyId") Long strategyId,
                         @Param("instanceId") Long instanceId,
                         @Param("side") String side,
                         @Param("quantity") BigDecimal quantity,
                         @Param("price") BigDecimal price,
                         @Param("fee") BigDecimal fee,
                         @Param("feeAsset") String feeAsset,
                         @Param("pnl") BigDecimal pnl,
                         @Param("tradeTime") LocalDateTime tradeTime);
    /**
     * AUDIT ONLY: lifetime executed BUY/SELL quantities are not signed open position
     * quantity. Net open interest cannot be inferred from a one-way BOTH account
     * without immutable open/close role and position-cycle attribution.
     */
    @Options(timeout = 3)
    @Select("""
        SELECT COUNT(*) AS fill_count,
               COALESCE(SUM(CASE WHEN side='BUY' THEN allocated_qty ELSE 0 END),0) AS bought_qty,
               COALESCE(SUM(CASE WHEN side='SELL' THEN allocated_qty ELSE 0 END),0) AS sold_qty,
               COALESCE(SUM(CASE WHEN side='BUY' THEN allocated_qty*allocated_price ELSE 0 END),0) AS bought_notional,
               COALESCE(SUM(CASE WHEN side='SELL' THEN allocated_qty*allocated_price ELSE 0 END),0) AS sold_notional,
               COALESCE(SUM(allocated_fee),0) AS total_fees,
               COUNT(DISTINCT allocated_fee_asset) AS fee_asset_count,
               MIN(allocated_fee_asset) AS fee_asset,
               COALESCE(SUM(realized_pnl),0) AS realized_pnl
        FROM quant_strategy_fill_allocation
        WHERE credential_id=#{credentialId} AND strategy_id=#{strategyId}
          AND strategy_instance_id=#{instanceId} AND symbol=#{symbol}
        """)
    Map<String,Object> summarizeVerifiedFills(@Param("credentialId") Long credentialId,
                                              @Param("strategyId") Long strategyId,
                                              @Param("instanceId") Long instanceId,
                                              @Param("symbol") String symbol);

}
