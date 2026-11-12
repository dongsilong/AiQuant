package org.dromara.quant.reconcile.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.quant.reconcile.domain.QuantTradingSyncTask;

/**
 * 手动订单与成交历史同步任务持久化访问。
 */
@Mapper
public interface QuantTradingSyncTaskMapper extends BaseMapperPlus<QuantTradingSyncTask, QuantTradingSyncTask> {
}
