package org.dromara.quant.risk.mapper;

import org.apache.ibatis.annotations.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 账户开仓额度的数据库访问接口。
 * 准入的账户行锁、读取当前占位、额度插入必须在同一个 REQUIRES_NEW 短事务内。
 * 不得把此处的单条原子 SQL 改成 BaseMapper 的先读后写。
 */
@Mapper
public interface OpenCapacityMapper {
    @Options(timeout = 3)
    @Select("SELECT id FROM quant_credential WHERE id=#{credentialId} FOR UPDATE")
    List<Long> lockCredential(@Param("credentialId") Long credentialId);

    @Options(timeout = 3)
    @Select("SELECT max_open_positions FROM quant_risk_config WHERE credential_id=#{credentialId}")
    List<Map<String, Object>> riskConfigs(@Param("credentialId") Long credentialId);

    @Options(timeout = 3)
    @Select("SELECT max_open_count FROM quant_strategy WHERE id=#{strategyId}")
    List<Integer> strategyLimits(@Param("strategyId") Long strategyId);

    @Options(timeout = 3)
    @Select("""
        SELECT symbol, position_side, quantity
        FROM quant_position WHERE credential_id=#{credentialId}
          AND (quantity<>0 OR quantity IS NULL)
        """)
    List<Map<String, Object>> activePositions(@Param("credentialId") Long credentialId);

    @Options(timeout = 3)
    @Select("""
        SELECT i.strategy_id, q.symbol, s.direction
        FROM quant_strategy_position_state s
        JOIN quant_strategy_instance i ON i.id=s.instance_id
        JOIN quant_strategy_symbol b ON b.id=i.strategy_symbol_id
        JOIN quant_symbol q ON q.id=b.symbol_id
        WHERE i.credential_id=#{credentialId}
          AND s.status NOT IN ('CLOSED','OPEN_PENDING') AND s.position_qty>0
        """)
    List<Map<String, Object>> activeStrategyStates(@Param("credentialId") Long credentialId);

    @Options(timeout = 3)
    @Select("""
        SELECT r.*, e.execution_status, e.exchange_order_status,
               s.status AS state_status, s.updated_at AS state_updated
        FROM quant_open_capacity r
        LEFT JOIN quant_execution_record e
          ON e.credential_id=r.credential_id AND e.idempotency_key=r.idempotency_key
        LEFT JOIN quant_strategy_position_state s ON s.instance_id=r.instance_id
        WHERE r.credential_id=#{credentialId} AND r.status='RESERVED'
        """)
    List<Map<String, Object>> reservedSlots(@Param("credentialId") Long credentialId);

    @Options(timeout = 3)
    @Update("""
        UPDATE quant_open_capacity SET status='RELEASED', updated_at=CURRENT_TIMESTAMP
        WHERE credential_id=#{credentialId} AND idempotency_key=#{key} AND status='RESERVED'
        """)
    int release(@Param("credentialId") Long credentialId, @Param("key") String key);

    @Options(timeout = 3)
    @Insert("""
        INSERT INTO quant_open_capacity
          (credential_id, idempotency_key, strategy_id, instance_id, symbol,
           position_side, reserved_margin, status, created_at, updated_at)
        VALUES (#{credentialId}, #{key}, #{strategyId}, #{instanceId}, #{symbol},
                #{direction}, #{margin}, 'RESERVED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        """)
    int insertReservation(@Param("credentialId") Long credentialId, @Param("key") String key, @Param("strategyId") Long strategyId, @Param("instanceId") Long instanceId, @Param(
        "symbol") String symbol, @Param("direction") String direction, @Param("margin") BigDecimal margin);

    @Options(timeout = 3)
    @Select("""
        SELECT COUNT(*) FROM quant_position WHERE credential_id=#{credentialId}
          AND symbol=#{symbol} AND (quantity<>0 OR quantity IS NULL)
        """)
    Long countActiveLocalPositions(@Param("credentialId") Long credentialId, @Param("symbol") String symbol);

    @Options(timeout = 3)
    @Select("""
        SELECT r.idempotency_key FROM quant_open_capacity r
        JOIN quant_execution_record e
          ON e.credential_id=r.credential_id AND e.idempotency_key=r.idempotency_key
        JOIN quant_strategy_position_state s ON s.instance_id=r.instance_id
        WHERE r.credential_id=#{credentialId} AND r.instance_id=#{instanceId}
          AND r.symbol=#{symbol} AND r.status='RESERVED'
          AND e.execution_status IN ('CONFIRMED','RECOVERED')
          AND e.exchange_order_status IN ('FILLED','CANCELED','EXPIRED','REJECTED')
          AND s.status='CLOSED' AND s.pending_action IS NULL AND s.updated_at>=r.created_at
        """)
    List<String> closedKeys(@Param("credentialId") Long credentialId, @Param("instanceId") Long instanceId, @Param("symbol") String symbol);

    @Options(timeout = 3)
    @Select("""
        SELECT r.idempotency_key FROM quant_open_capacity r
        JOIN quant_execution_record e
          ON e.credential_id=r.credential_id AND e.idempotency_key=r.idempotency_key
        WHERE r.credential_id=#{credentialId} AND r.status='RESERVED'
          AND e.execution_status IN ('CONFIRMED','RECOVERED')
          AND e.exchange_order_status='FILLED'
        """)
    List<String> balanceCoverage(@Param("credentialId") Long credentialId);

    @Options(timeout = 3)
    @Select("""
        SELECT r.idempotency_key, r.reserved_margin, e.execution_status
        FROM quant_open_capacity r
        LEFT JOIN quant_execution_record e
          ON e.credential_id=r.credential_id AND e.idempotency_key=r.idempotency_key
        WHERE r.credential_id=#{credentialId} AND r.status='RESERVED'
        """)
    List<Map<String, Object>> marginReservations(@Param("credentialId") Long credentialId);

    @Options(timeout = 3)
    @Update("""
        UPDATE quant_open_capacity SET status='RELEASED', updated_at=CURRENT_TIMESTAMP
        WHERE credential_id=#{credentialId} AND idempotency_key=#{key}
          AND EXISTS (SELECT 1 FROM quant_execution_record e
                      WHERE e.credential_id=#{credentialId}
                        AND e.idempotency_key=#{key} AND e.execution_status='FAILED')
        """)
    int releaseFailed(@Param("credentialId") Long credentialId, @Param("key") String key);
}
