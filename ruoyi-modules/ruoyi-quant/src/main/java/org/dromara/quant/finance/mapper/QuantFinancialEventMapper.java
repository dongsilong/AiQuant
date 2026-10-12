package org.dromara.quant.finance.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.quant.finance.domain.QuantFinancialEvent;
import org.dromara.quant.finance.domain.vo.QuantFinancialEventVo;

/**
 * QuantFinancialEventMapper，负责对应实体的 MyBatis-Plus 持久化操作。
 */
@Mapper
public interface QuantFinancialEventMapper extends BaseMapperPlus<QuantFinancialEvent, QuantFinancialEventVo> {
}
