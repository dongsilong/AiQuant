package org.dromara.quant.runtime.mapper;

import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.quant.runtime.domain.QuantStrategyPositionState;
import org.apache.ibatis.annotations.Param;
import java.math.BigDecimal;

/**
 * QuantStrategyPositionStateMapper，负责对应实体的 MyBatis-Plus 持久化操作。
 */
public interface QuantStrategyPositionStateMapper extends BaseMapperPlus<QuantStrategyPositionState, QuantStrategyPositionState> {

    int updateMaximumReturnRate(@Param("instanceId") Long instanceId, @Param("positionId") Long positionId, @Param("cycleKey") String cycleKey,
                                @Param("rate") BigDecimal rate);

    int resetClosedMaximumReturnRate(@Param("instanceId") Long instanceId);
}
