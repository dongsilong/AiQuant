package org.dromara.quant.order.mapper;

import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.quant.order.domain.QuantOrder;

/**
 * QuantOrderMapper，负责对应实体的 MyBatis-Plus 持久化操作。
 */
public interface QuantOrderMapper extends BaseMapperPlus<QuantOrder, QuantOrder> {
    /**
     * A late WS/REST snapshot may not decrease cumulative fills, roll back a terminal
     * state, overwrite another strategy's ownership, or change the exchange order ID.
     * All guards must live in the SQL UPDATE to remain safe across app nodes.
     */
    @Options(timeout = 3)
    @Update("""
        UPDATE quant_order
        SET exchange_order_id=COALESCE(exchange_order_id, #{fact.exchangeOrderId}),
            client_order_id=CASE WHEN client_order_id LIKE 'EXCHANGE:%'
                AND #{fact.clientOrderId} NOT LIKE 'EXCHANGE:%'
                THEN #{fact.clientOrderId} ELSE client_order_id END,
            symbol=#{fact.symbol}, side=#{fact.side}, order_type=#{fact.orderType},
            status=#{fact.status}, price=COALESCE(#{fact.price},price),
            original_quantity=COALESCE(#{fact.originalQuantity},original_quantity),
            executed_quantity=COALESCE(#{fact.executedQuantity},executed_quantity),
            strategy_id=COALESCE(strategy_id, #{fact.strategyId}),
            strategy_instance_id=COALESCE(strategy_instance_id, #{fact.strategyInstanceId}),
            position_side=COALESCE(position_side, #{fact.positionSide}),
            reduce_only=COALESCE(reduce_only, #{fact.reduceOnly}),
            updated_at=#{fact.updatedAt}
        WHERE id=#{fact.id} AND credential_id=#{fact.credentialId}
          AND (exchange_order_id IS NULL OR #{fact.exchangeOrderId} IS NULL
               OR exchange_order_id=#{fact.exchangeOrderId})
          AND (client_order_id=#{fact.clientOrderId}
               OR client_order_id LIKE 'EXCHANGE:%'
               OR #{fact.clientOrderId} LIKE 'EXCHANGE:%')
          AND (strategy_id IS NULL OR #{fact.strategyId} IS NULL OR strategy_id=#{fact.strategyId})
          AND (strategy_instance_id IS NULL OR #{fact.strategyInstanceId} IS NULL
               OR strategy_instance_id=#{fact.strategyInstanceId})
          AND (executed_quantity IS NULL OR
               (#{fact.executedQuantity} IS NOT NULL
                AND executed_quantity<=#{fact.executedQuantity}))
          AND (status IS NULL OR status NOT IN
               ('FILLED','CANCELED','EXPIRED','EXPIRED_IN_MATCH','REJECTED')
               OR status=#{fact.status})
          AND (status IS NULL OR status NOT IN ('PARTIALLY_FILLED','PENDING_CANCEL')
               OR #{fact.status} NOT IN ('NEW','PENDING_NEW'))
        """)
    int mergeExchangeFactIfMonotonic(@Param("fact") QuantOrder fact);
}
