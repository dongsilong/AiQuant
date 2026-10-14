package org.dromara.quant.runtime.coordination.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Read-only cross-instance strategy exposure fact; keep SQL centralised outside runtime orchestration.
 */
@Mapper
public interface StrategyRuntimeLedgerMapper {
    @Options(timeout = 3)
    @Select("""
        SELECT COUNT(*) FROM quant_strategy_position_state s
        JOIN quant_strategy_instance i ON i.id=s.instance_id
        WHERE i.strategy_id=#{strategyId} AND i.credential_id=#{credentialId}
          AND s.status IN ('OPEN_PENDING','OPEN','EXIT_PENDING','PROTECTION_CLEANUP')
          AND s.position_qty>0
        """)
    Long countStrategyExposures(@Param("strategyId") Long strategyId, @Param("credentialId") Long credentialId);
}
