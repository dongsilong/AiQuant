package org.dromara.quant.config.mapper;

import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.quant.config.domain.QuantSymbol;
import org.dromara.quant.config.domain.vo.QuantSymbolVo;

/**
 * QuantSymbolMapper，负责对应实体的 MyBatis-Plus 持久化操作。
 */
public interface QuantSymbolMapper extends BaseMapperPlus<QuantSymbol, QuantSymbolVo> {

}
