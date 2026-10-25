package org.dromara.quant.order.mapper;

import org.apache.ibatis.annotations.Param;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.quant.order.domain.QuantOrder;

/**
 * QuantOrderMapper，负责对应实体的 MyBatis-Plus 持久化操作。
 */
public interface QuantOrderMapper extends BaseMapperPlus<QuantOrder, QuantOrder> {

    /** Distinct symbols already known to this credential, including symbols whose positions are now closed. */
    java.util.List<String> selectHistoricalSymbolsByCredential(@Param("credentialId") Long credentialId);

    int claimPostFillBalanceSnapshot(@Param("id") Long id, @Param("requestedAt") java.time.LocalDateTime requestedAt);

    int savePostFillBalanceSnapshot(@Param("id") Long id, @Param("balance") java.math.BigDecimal balance, @Param("observedAt") java.time.LocalDateTime observedAt);

    /**
     * A late WS/REST snapshot may not decrease cumulative fills, roll back a terminal
     * state, overwrite another strategy's ownership, or change the exchange order ID.
     * All guards must live in the SQL UPDATE to remain safe across app nodes.
     */

    int mergeExchangeFactIfMonotonic(@Param("fact") QuantOrder fact);
}
