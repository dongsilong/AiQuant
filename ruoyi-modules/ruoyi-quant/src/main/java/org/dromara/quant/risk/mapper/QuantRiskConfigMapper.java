package org.dromara.quant.risk.mapper;

import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.quant.risk.domain.QuantRiskConfig;
import org.dromara.quant.risk.domain.vo.QuantRiskConfigVo;

/**
 * QuantRiskConfigMapper，负责对应实体的 MyBatis-Plus 持久化操作。
 */
public interface QuantRiskConfigMapper extends BaseMapperPlus<QuantRiskConfig, QuantRiskConfigVo> {
}
