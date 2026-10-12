package org.dromara.quant.account.mapper;

import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.quant.account.domain.QuantTrade;
import org.dromara.quant.account.domain.vo.QuantTradeVo;

/**
 * QuantTradeMapper，负责对应实体的 MyBatis-Plus 持久化操作。
 */
public interface QuantTradeMapper extends BaseMapperPlus<QuantTrade, QuantTradeVo> {
}
