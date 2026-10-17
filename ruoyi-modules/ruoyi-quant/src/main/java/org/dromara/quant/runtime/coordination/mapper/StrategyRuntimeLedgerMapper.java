package org.dromara.quant.runtime.coordination.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * Read-only cross-instance strategy exposure fact; keep SQL centralised outside runtime orchestration.
 */
@Mapper
public interface StrategyRuntimeLedgerMapper {
    @Options(timeout = 3)
    @Select("""
        SELECT COUNT(*) FROM quant_strategy_position_state s
        JOIN quant_strategy_instance i ON i.id=s.instance_id
        LEFT JOIN quant_position p ON p.id=s.position_id
        WHERE i.strategy_id=#{strategyId} AND i.credential_id=#{credentialId}
          AND s.status IN ('OPEN_PENDING','OPEN','EXIT_PENDING','PROTECTION_CLEANUP')
          AND s.position_qty>0
          AND (s.status <> 'OPEN' OR p.quantity > 0 OR p.quantity IS NULL)
        """)
    Long countStrategyExposures(@Param("strategyId") Long strategyId, @Param("credentialId") Long credentialId);

    @Options(timeout = 3)
    @Select("""
        SELECT s.instance_id AS instanceId, i.status AS instanceStatus,
               s.position_id AS positionId, s.status AS positionStatus,
               s.position_qty AS strategyQty, s.pending_action AS pendingAction,
               s.pending_order_id AS pendingOrderId, s.pending_client_order_id AS pendingClientOrderId,
               p.symbol AS symbol, p.position_side AS positionSide, p.quantity AS localPositionQty
        FROM quant_strategy_position_state s
        JOIN quant_strategy_instance i ON i.id=s.instance_id
        LEFT JOIN quant_position p ON p.id=s.position_id
        WHERE i.strategy_id=#{strategyId} AND i.credential_id=#{credentialId}
          AND s.status IN ('OPEN_PENDING','OPEN','EXIT_PENDING','PROTECTION_CLEANUP')
          AND s.position_qty>0
          AND (s.status <> 'OPEN' OR p.quantity > 0 OR p.quantity IS NULL)
        ORDER BY s.updated_at ASC, s.instance_id ASC
        """)
    List<Map<String, Object>> findStrategyExposures(@Param("strategyId") Long strategyId, @Param("credentialId") Long credentialId);
}
