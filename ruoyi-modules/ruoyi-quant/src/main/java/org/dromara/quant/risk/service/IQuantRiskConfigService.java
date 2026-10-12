package org.dromara.quant.risk.service;

import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.quant.risk.domain.bo.QuantRiskConfigBo;
import org.dromara.quant.risk.domain.vo.QuantRiskConfigVo;

/**
 * IQuantRiskConfigService，封装对应领域业务。
 */
public interface IQuantRiskConfigService {
    /**
     * 按条件分页查询。
     */
    PageResult<QuantRiskConfigVo> queryPageList(QuantRiskConfigBo bo, PageQuery pageQuery);

    /**
     * 按主键查询。
     */
    QuantRiskConfigVo queryById(Long id);

    /**
     * 根据业务入参新增记录。
     */
    QuantRiskConfigVo insertByBo(QuantRiskConfigBo bo);

    /**
     * 根据业务入参更新记录。
     */
    QuantRiskConfigVo updateByBo(QuantRiskConfigBo bo);

    /**
     * 业务校验后批量删除记录。
     */
    Boolean deleteWithValidByIds(java.util.Collection<Long> ids);
}
