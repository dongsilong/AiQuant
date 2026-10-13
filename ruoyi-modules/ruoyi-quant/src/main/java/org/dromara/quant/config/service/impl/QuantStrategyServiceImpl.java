package org.dromara.quant.config.service.impl;

import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.quant.config.domain.bo.QuantStrategyBo;
import org.dromara.quant.config.domain.vo.QuantStrategyVo;
import org.dromara.quant.config.service.IQuantStrategyService;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;

/**
 * 策略定义服务。
 */
@Service
public class QuantStrategyServiceImpl implements IQuantStrategyService {

    @Override
    public PageResult<QuantStrategyVo> queryPage(PageQuery query) {
        return null;
    }

    @Override
    public QuantStrategyVo get(Long id) {
        return null;
    }

    @Override
    public QuantStrategyVo insert(QuantStrategyBo entity) {
        return null;
    }

    @Override
    public QuantStrategyVo update(QuantStrategyBo entity) {
        return null;
    }

    @Override
    public Boolean delete(Collection<Long> ids) {
        return null;
    }

    @Override
    public boolean start(Long id) {
        return false;
    }

    @Override
    public boolean stop(Long id) {
        return false;
    }

    @Override
    public void checkAccess(QuantStrategyBo strategy) {

    }

    @Override
    public List<Long> accessibleStrategyIds() {
        return List.of();
    }
}
