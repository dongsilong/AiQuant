package org.dromara.quant.protection.mapper;

import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.quant.protection.domain.QuantProtectionOrder;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
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

    /** CAS 持久化交易所保护单事实，避免远端查询期间的旧实体覆盖新状态。 */
    @Update("""
        UPDATE quant_protection_order
        SET exchange_order_id = #{exchangeOrderId},
            status = #{status},
            executed_quantity = GREATEST(COALESCE(executed_quantity, 0), #{executedQuantity})
        WHERE id = #{id}
          AND credential_id = #{credentialId}
          AND client_order_id = #{clientOrderId}
          AND status <=> #{expectedStatus}
          AND exchange_order_id <=> #{expectedExchangeOrderId}
          AND executed_quantity <=> #{expectedExecutedQuantity}
        """)
    int updateExchangeFactIfUnchanged(@Param("id") Long id, @Param("credentialId") Long credentialId,
                                      @Param("clientOrderId") String clientOrderId, @Param("expectedStatus") String expectedStatus,
                                      @Param("expectedExchangeOrderId") Long expectedExchangeOrderId,
                                      @Param("expectedExecutedQuantity") BigDecimal expectedExecutedQuantity,
                                      @Param("exchangeOrderId") Long exchangeOrderId, @Param("status") String status,
                                      @Param("executedQuantity") BigDecimal executedQuantity);
}
