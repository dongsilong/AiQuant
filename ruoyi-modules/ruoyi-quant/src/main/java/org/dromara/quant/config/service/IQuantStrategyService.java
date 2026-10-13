package org.dromara.quant.config.service;

import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.quant.config.domain.bo.QuantStrategyBo;
import org.dromara.quant.config.domain.vo.QuantStrategyVo;

import java.util.Collection;
import java.util.List;

/**
 * 策略定义服务。
 */
public interface IQuantStrategyService {

    /**
     * 分页查询业务数据。
     */
    PageResult<QuantStrategyVo> queryPage(PageQuery query);

    /**
     * 获取对应业务数据。
     */
    QuantStrategyVo get(Long id);

    /**
     * 新增业务数据。
     */
    QuantStrategyVo insert(QuantStrategyBo entity);

    /**
     * 更新业务数据。
     */
    QuantStrategyVo update(QuantStrategyBo entity);

    /**
     * 删除业务数据。
     */
    Boolean delete(Collection<Long> ids);

    /**
     * 启动组件或底层订阅。
     */
    boolean start(Long id);

    /**
     * 执行与启动接口相同的服务端配置预检。
     */
    //    StrategyPreflightResult preflight(Long id);

    /**
     * 停止组件或订阅并释放资源。
     */
    boolean stop(Long id);

    /**
     * 校验当前用户是否获得该策略授权。
     */
    void checkAccess(QuantStrategyBo strategy);

    /**
     * 返回当前用户可访问的策略 ID；超级管理员返回 null 表示不限制。
     */
    List<Long> accessibleStrategyIds();

}
