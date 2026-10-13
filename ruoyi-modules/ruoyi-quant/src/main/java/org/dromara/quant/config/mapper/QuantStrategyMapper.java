package org.dromara.quant.config.mapper;

import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.quant.config.domain.QuantStrategy;
import org.dromara.quant.config.domain.vo.QuantStrategyVo;

/**
 * QuantStrategyMapper，负责对应实体的 MyBatis-Plus 持久化操作。
 */
public interface QuantStrategyMapper extends BaseMapperPlus<QuantStrategy, QuantStrategyVo> {
}
