package org.dromara.quant.runtime.mapper;

import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.quant.runtime.domain.QuantStrategyPositionState;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

/**
 * QuantStrategyPositionStateMapper，负责对应实体的 MyBatis-Plus 持久化操作。
 */
public interface QuantStrategyPositionStateMapper extends BaseMapperPlus<QuantStrategyPositionState, QuantStrategyPositionState> {

    @Update("UPDATE quant_strategy_position_state SET maximum_return_rate = GREATEST(COALESCE(maximum_return_rate, 0), #{rate}), updated_at = CURRENT_TIMESTAMP(6) "
        + "WHERE instance_id = #{instanceId} AND position_id = #{positionId} AND position_cycle_key = #{cycleKey} AND status <> 'CLOSED'")
    int updateMaximumReturnRate(@Param("instanceId") Long instanceId, @Param("positionId") Long positionId, @Param("cycleKey") String cycleKey,
                                @Param("rate") BigDecimal rate);

    @Update("UPDATE quant_strategy_position_state SET maximum_return_rate = 0 WHERE instance_id = #{instanceId} AND status = 'CLOSED'")
    int resetClosedMaximumReturnRate(@Param("instanceId") Long instanceId);
}
