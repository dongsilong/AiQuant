package org.dromara.quant.market.mapper;

import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.quant.market.domain.QuantSymbol;
import org.dromara.quant.market.domain.vo.QuantSymbolVo;

/**
 * QuantSymbolMapper，负责对应实体的 MyBatis-Plus 持久化操作。
 */
public interface QuantSymbolMapper extends BaseMapperPlus<QuantSymbol, QuantSymbolVo> {
}
