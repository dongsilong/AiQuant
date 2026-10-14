package org.dromara.quant.execution;

import org.apache.ibatis.annotations.*;
import java.util.Map;

@Mapper
public interface StrategyOrderIntentMapper {
    @Options(timeout=3)
    @Insert("""
        INSERT INTO quant_strategy_order_intent
          (credential_id,symbol,exchange_order_id,strategy_id,strategy_instance_id,
           position_side,fill_role,exchange_side,client_order_id,created_at)
        VALUES (#{credentialId},#{symbol},#{orderId},#{strategyId},#{instanceId},
                #{positionSide},#{role},#{exchangeSide},#{clientOrderId},NOW(6))
        """)
    int insert(@Param("credentialId") long credentialId,@Param("symbol") String symbol,
               @Param("orderId") long orderId,@Param("strategyId") long strategyId,
               @Param("instanceId") long instanceId,@Param("positionSide") String positionSide,
               @Param("role") String role,@Param("exchangeSide") String exchangeSide,
               @Param("clientOrderId") String clientOrderId);

    @Options(timeout=3)
    @Select("""
        SELECT credential_id,symbol,exchange_order_id,strategy_id,strategy_instance_id,
               position_side,fill_role,exchange_side,client_order_id
        FROM quant_strategy_order_intent
        WHERE credential_id=#{credentialId} AND symbol=#{symbol}
          AND exchange_order_id=#{orderId}
        """)
    Map<String,Object> find(@Param("credentialId") long credentialId,
                            @Param("symbol") String symbol,@Param("orderId") long orderId);
    @Options(timeout=3)
    @Select("""
        SELECT credential_id,symbol,idempotency_key,client_order_id,strategy_id,
               strategy_instance_id,operation_type,position_side,exchange_side,lease_epoch
        FROM quant_strategy_pre_dispatch_intent
        WHERE credential_id=#{credentialId} AND idempotency_key=#{key}
        """)
    Map<String,Object> pending(@Param("credentialId") long credentialId,
                               @Param("key") String key);

    /** Promote ONLY the pre-send immutable fact after exact exchange identity. */
    @Options(timeout=3)
    @Insert("""
        INSERT INTO quant_strategy_order_intent
        (credential_id,symbol,exchange_order_id,strategy_id,strategy_instance_id,
         position_side,fill_role,exchange_side,client_order_id,created_at)
        SELECT credential_id,symbol,#{orderId},strategy_id,strategy_instance_id,
               position_side,operation_type,exchange_side,client_order_id,NOW(6)
        FROM quant_strategy_pre_dispatch_intent
        WHERE credential_id=#{credentialId} AND symbol=#{symbol}
          AND idempotency_key=#{key} AND client_order_id=#{clientOrderId}
          AND EXISTS (
            SELECT 1 FROM quant_order_dispatch_ticket t
            WHERE t.credential_id=#{credentialId}
              AND t.idempotency_key=#{key}
              AND t.symbol=#{symbol}
              AND t.client_order_id=#{clientOrderId}
              AND t.operation_type=quant_strategy_pre_dispatch_intent.operation_type
          )
        """)
    int promote(@Param("credentialId") long credentialId,
                @Param("symbol") String symbol,@Param("key") String key,
                @Param("clientOrderId") String clientOrderId,
                @Param("orderId") long orderId);

    @Options(timeout=3)
    @Update("""
        UPDATE quant_order
        SET strategy_id=#{strategyId},strategy_instance_id=#{instanceId},
            position_side=#{positionSide},reduce_only=#{reduceOnly}
        WHERE id=#{localId} AND credential_id=#{credentialId}
          AND symbol=#{symbol} AND exchange_order_id=#{orderId}
          AND client_order_id=#{clientOrderId}
          AND (strategy_id IS NULL OR strategy_id=#{strategyId})
          AND (strategy_instance_id IS NULL OR strategy_instance_id=#{instanceId})
          AND (position_side IS NULL OR position_side=#{positionSide})
          AND (reduce_only IS NULL OR reduce_only=#{reduceOnly})
        """)
    int bindRecoveredOrder(@Param("localId") long localId,
                           @Param("credentialId") long credentialId,
                           @Param("symbol") String symbol,@Param("orderId") long orderId,
                           @Param("clientOrderId") String clientOrderId,
                           @Param("strategyId") long strategyId,@Param("instanceId") long instanceId,
                           @Param("positionSide") String positionSide,
                           @Param("reduceOnly") boolean reduceOnly);

}
