package org.dromara.quant.protection.mapper;

import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.quant.protection.domain.QuantProtectionOrder;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * QuantProtectionOrderMapper，负责对应实体的 MyBatis-Plus 持久化操作。
 */
public interface QuantProtectionOrderMapper extends BaseMapperPlus<QuantProtectionOrder, QuantProtectionOrder> {
    /** 仅允许当前精确持仓周期提供保护意图的策略来源。 */
    @Options(timeout = 3)
    @Select("""
        SELECT i.strategy_id, s.instance_id
        FROM quant_strategy_position_state s
        JOIN quant_strategy_instance i ON i.id=s.instance_id
        JOIN quant_strategy_symbol b ON b.id=i.strategy_symbol_id
        JOIN quant_symbol q ON q.id=b.symbol_id
        WHERE i.credential_id=#{credentialId} AND s.position_id=#{positionId}
          AND s.position_cycle_key=#{cycleKey} AND q.symbol=#{symbol} AND s.direction=#{direction}
        """)
    List<Map<String, Object>> cycleOwners(@Param("credentialId") Long credentialId, @Param("positionId") Long positionId,
                                         @Param("cycleKey") String cycleKey, @Param("symbol") String symbol, @Param("direction") String direction);

    @Options(timeout = 3)
    @Update("""
        UPDATE quant_protection_order SET strategy_id=#{strategyId}, strategy_instance_id=#{instanceId}
        WHERE id=#{id} AND credential_id=#{credentialId} AND position_cycle_key=#{cycleKey}
          AND strategy_id IS NULL AND strategy_instance_id IS NULL
        """)
    int bindCycleOwner(@Param("id") Long id, @Param("credentialId") Long credentialId, @Param("cycleKey") String cycleKey,
                       @Param("strategyId") Long strategyId, @Param("instanceId") Long instanceId);
}
