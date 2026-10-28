package org.dromara.quant.protection.mapper;

import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.quant.protection.domain.QuantProtectionOrder;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Map;

/**
 * QuantProtectionOrderMapper，负责对应实体的 MyBatis-Plus 持久化操作。
 */
public interface QuantProtectionOrderMapper extends BaseMapperPlus<QuantProtectionOrder, QuantProtectionOrder> {
    /** 仅允许当前精确持仓周期提供保护意图的策略来源。 */

    List<Map<String, Object>> cycleOwners(@Param("credentialId") Long credentialId, @Param("positionId") Long positionId,
                                         @Param("cycleKey") String cycleKey, @Param("symbol") String symbol, @Param("direction") String direction);

    int bindCycleOwner(@Param("id") Long id, @Param("credentialId") Long credentialId, @Param("cycleKey") String cycleKey,
                       @Param("strategyId") Long strategyId, @Param("instanceId") Long instanceId);

    /**
     * 原子地把精确保护单从非 WORKING 状态转为 WORKING。
     * 只有真正完成状态迁移的调用返回 1，供并发确认路径抑制重复确认日志。
     */
    int markWorkingIfNotWorking(@Param("id") Long id, @Param("credentialId") Long credentialId, @Param("symbol") String symbol,
                                @Param("clientOrderId") String clientOrderId, @Param("exchangeOrderId") Long exchangeOrderId);
}
