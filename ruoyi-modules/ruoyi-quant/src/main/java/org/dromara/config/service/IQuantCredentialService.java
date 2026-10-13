package org.dromara.config.service;

import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.config.domain.bo.QuantCredentialBo;
import org.dromara.config.domain.vo.QuantCredentialVo;

import java.util.Collection;

/**
 * 凭证管理服务。
 */
public interface IQuantCredentialService {
    /**
     * 按条件分页查询。
     */
    PageResult<QuantCredentialVo> queryPageList(QuantCredentialBo bo, PageQuery pageQuery);

    /**
     * 按主键查询。
     */
    QuantCredentialVo queryById(Long id);

    /**
     * 根据业务入参新增记录。
     */
    QuantCredentialVo insertByBo(QuantCredentialBo bo);

    /**
     * 根据业务入参更新记录。
     */
    QuantCredentialVo updateByBo(QuantCredentialBo bo);

    /**
     * 业务校验后批量删除记录。
     */
    Boolean deleteWithValidByIds(Collection<Long> ids);

    /**
     * 修改启停状态。
     */
    void changeStatus(Long id, String status);

    /**
     * 验证交易所连通性。
     */
    void testConnectivity(Long id);
}
