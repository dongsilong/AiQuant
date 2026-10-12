package org.dromara.quant.market.service;

import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.quant.market.domain.bo.QuantSymbolBo;
import org.dromara.quant.market.domain.vo.QuantSymbolVo;

/**
 * IQuantSymbolService，封装对应领域业务。
 */
public interface IQuantSymbolService {
    /**
     * 按条件分页查询。
     */
    PageResult<QuantSymbolVo> queryPageList(QuantSymbolBo bo, PageQuery pageQuery);

    /**
     * 按主键查询。
     */
    QuantSymbolVo queryById(Long id);

    /**
     * 根据业务入参新增记录。
     */
    QuantSymbolVo insertByBo(QuantSymbolBo bo);

    /**
     * 根据业务入参更新记录。
     */
    QuantSymbolVo updateByBo(QuantSymbolBo bo);

    /**
     * 业务校验后批量删除记录。
     */
    Boolean deleteWithValidByIds(java.util.Collection<Long> ids);

    /**
     * 从交易所同步基础元数据。
     */
    int syncFromExchange();
}
