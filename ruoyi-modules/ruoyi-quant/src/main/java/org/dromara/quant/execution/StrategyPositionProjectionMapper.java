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
    @Options(timeout = 3)
    @Select("""
        SELECT strategy_id,strategy_instance_id,position_side,fill_role,
               exchange_side,client_order_id
        FROM quant_strategy_order_intent
        WHERE credential_id=#{credentialId} AND symbol=#{symbol}
          AND exchange_order_id=#{orderId}
        """)
    Map<String, Object> verifiedIntent(@Param("credentialId") long credentialId, @Param("symbol") String symbol, @Param("orderId") long orderId);

    @Options(timeout = 3)
    @Insert("""
        INSERT IGNORE INTO quant_strategy_position_inventory
        (credential_id,symbol,strategy_instance_id,position_side,strategy_id,updated_at)
        VALUES (#{credentialId},#{symbol},#{instanceId},#{side},#{strategyId},NOW(6))
        """)
    int ensure(@Param("credentialId") long credentialId, @Param("symbol") String symbol, @Param("instanceId") long instanceId, @Param("side") String side,
               @Param("strategyId") long strategyId);

    @Options(timeout = 3)
    @Select("""
        SELECT strategy_id,open_quantity,cost_basis,realized_pnl,commissions,commission_asset,
               cycle_number,cycle_id,last_trade_time,last_trade_id
        FROM quant_strategy_position_inventory
        WHERE credential_id=#{credentialId} AND symbol=#{symbol}
          AND strategy_instance_id=#{instanceId} AND position_side=#{side}
        FOR UPDATE
        """)
    Map<String, Object> lock(@Param("credentialId") long credentialId, @Param("symbol") String symbol, @Param("instanceId") long instanceId, @Param("side") String side);

    @Options(timeout = 3)
    @Select("""
        SELECT strategy_id,strategy_instance_id,exchange_order_id,position_side,
               fill_role,exchange_side,quantity,fill_price,commission,commission_asset,realized_pnl,trade_time
        FROM quant_strategy_position_fill_event
        WHERE credential_id=#{credentialId} AND symbol=#{symbol}
          AND exchange_trade_id=#{tradeId}
        """)
    Map<String, Object> existing(@Param("credentialId") long credentialId, @Param("symbol") String symbol, @Param("tradeId") long tradeId);

    @Options(timeout = 3)
    @Insert("""
        INSERT INTO quant_strategy_position_cycle
        (id,credential_id,symbol,strategy_instance_id,position_side,
         cycle_number,opened_at)
        VALUES (#{id},#{credentialId},#{symbol},#{instanceId},#{side},#{cycle},#{openedAt})
        """)
    int openCycle(@Param("id") long id, @Param("credentialId") long credentialId, @Param("symbol") String symbol, @Param("instanceId") long instanceId,
                  @Param("side") String side, @Param("cycle") long cycle, @Param("openedAt") LocalDateTime openedAt);

    @Options(timeout = 3)
    @Update("""
        UPDATE quant_strategy_position_cycle SET closed_at=#{closedAt}
        WHERE id=#{cycleId} AND closed_at IS NULL
        """)
    int closeCycle(@Param("cycleId") long cycleId, @Param("closedAt") LocalDateTime closedAt);

    @Options(timeout = 3)
    @Insert("""
        INSERT INTO quant_strategy_position_fill_event
        (id,credential_id,symbol,strategy_id,strategy_instance_id,cycle_id,
         exchange_trade_id,exchange_order_id,position_side,fill_role,exchange_side,
         quantity,fill_price,commission,commission_asset,realized_pnl,trade_time)
        VALUES (#{id},#{credentialId},#{symbol},#{strategyId},#{instanceId},
                #{cycleId},#{tradeId},#{orderId},#{side},#{role},#{exchangeSide},
                #{quantity},#{price},#{fee},#{feeAsset},#{pnl},#{tradeTime})
        """)
    int insertFill(@Param("id") long id, @Param("credentialId") long credentialId, @Param("symbol") String symbol, @Param("strategyId") long strategyId,
                   @Param("instanceId") long instanceId, @Param("cycleId") long cycleId, @Param("tradeId") long tradeId, @Param("orderId") long orderId,
                   @Param("side") String side, @Param("role") String role, @Param("exchangeSide") String exchangeSide, @Param("quantity") BigDecimal quantity,
                   @Param("price") BigDecimal price, @Param("fee") BigDecimal fee, @Param("feeAsset") String feeAsset, @Param("pnl") BigDecimal pnl,
                   @Param("tradeTime") LocalDateTime tradeTime);

    @Options(timeout = 3)
    @Update("""
        UPDATE quant_strategy_position_inventory
        SET open_quantity=#{quantity},cost_basis=#{cost},
            realized_pnl=#{pnl},commissions=#{fees},commission_asset=#{feeAsset},
            cycle_number=#{cycle},cycle_id=#{cycleId},
            last_trade_time=#{tradeTime},last_trade_id=#{tradeId},updated_at=NOW(6)
        WHERE credential_id=#{credentialId} AND symbol=#{symbol}
          AND strategy_instance_id=#{instanceId} AND position_side=#{side}
          AND strategy_id=#{strategyId}
          AND (commission_asset IS NULL OR commission_asset=#{feeAsset})
        """)
    int update(@Param("credentialId") long credentialId, @Param("symbol") String symbol, @Param("instanceId") long instanceId, @Param("side") String side,
               @Param("strategyId") long strategyId, @Param("quantity") BigDecimal quantity, @Param("cost") BigDecimal cost, @Param("pnl") BigDecimal pnl,
               @Param("fees") BigDecimal fees, @Param("feeAsset") String feeAsset, @Param("cycle") long cycle, @Param("cycleId") Long cycleId,
               @Param("tradeTime") LocalDateTime tradeTime, @Param("tradeId") long tradeId);
}
