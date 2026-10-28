package org.dromara.quant.protection.mapper;

import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.quant.protection.domain.QuantProtectionOrder;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
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
     * Atomically transition an exact, non-terminal protection intent to WORKING.
     * A zero row count means another worker already transitioned it or the durable
     * identity/status no longer permits confirmation; callers must re-read and verify.
     */
    int markWorkingIfNotWorking(@Param("id") Long id, @Param("credentialId") Long credentialId,
                                @Param("clientOrderId") String clientOrderId, @Param("exchangeOrderId") Long exchangeOrderId,
                                @Param("executedQuantity") BigDecimal executedQuantity);
}
