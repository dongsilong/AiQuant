package org.dromara.quant.runtime.mapper;

import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.quant.runtime.domain.QuantStrategyHedgeRound;
import org.apache.ibatis.annotations.Mapper;

/**
 * 双开轮次协调事实 Mapper。
 */
@Mapper
public interface QuantStrategyHedgeRoundMapper extends BaseMapperPlus<QuantStrategyHedgeRound, QuantStrategyHedgeRound> {
}
