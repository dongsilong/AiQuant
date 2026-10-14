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
}
