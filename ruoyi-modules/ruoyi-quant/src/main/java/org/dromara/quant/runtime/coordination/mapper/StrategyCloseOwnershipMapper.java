package org.dromara.quant.runtime.coordination.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Map;

/**
 * Validates a strategy's persisted ownership of a planned LIVE reduction.
 * The strategy state is not interchangeable with a shared Binance account position.
 */
@Mapper
public interface StrategyCloseOwnershipMapper {
    @Options(timeout = 3)
    @Select("""
        SELECT s.status AS status, s.direction AS direction,
               s.position_qty AS position_qty, s.pending_action AS pending_action,
               s.pending_idempotency_key AS pending_idempotency_key,
               s.unsafe_recovery AS unsafe_recovery
        FROM quant_strategy_position_state s
        JOIN quant_strategy_instance i ON i.id=s.instance_id
        JOIN quant_strategy_symbol b ON b.id=i.strategy_symbol_id
        JOIN quant_symbol q ON q.id=b.symbol_id
        WHERE s.instance_id=#{instanceId} AND i.credential_id=#{credentialId}
          AND i.strategy_id=#{strategyId} AND q.symbol=#{symbol}
        """)
    Map<String, Object> pendingOwnedExit(@Param("instanceId") Long instanceId,
                                         @Param("credentialId") Long credentialId,
                                         @Param("strategyId") Long strategyId,
                                         @Param("symbol") String symbol);
}
