package org.dromara.quant.execution;

import org.apache.ibatis.annotations.*;

import java.util.Map;

/**
 * 持久化策略订单意图及其派发状态。
 */
@Mapper
public interface StrategyOrderIntentMapper {

    int insert(@Param("credentialId") long credentialId, @Param("symbol") String symbol, @Param("orderId") long orderId, @Param("strategyId") long strategyId, @Param("instanceId"
    ) long instanceId, @Param("positionSide") String positionSide, @Param("role") String role, @Param("exchangeSide") String exchangeSide,
               @Param("clientOrderId") String clientOrderId);

    Map<String, Object> find(@Param("credentialId") long credentialId, @Param("symbol") String symbol, @Param("orderId") long orderId);

    Map<String, Object> pending(@Param("credentialId") long credentialId, @Param("key") String key);

    /**
     * Promote ONLY the pre-send immutable fact after exact exchange identity.
     */

    int promote(@Param("credentialId") long credentialId, @Param("symbol") String symbol, @Param("key") String key, @Param("clientOrderId") String clientOrderId, @Param("orderId"
    ) long orderId);

    int bindRecoveredOrder(@Param("localId") long localId, @Param("credentialId") long credentialId, @Param("symbol") String symbol, @Param("orderId") long orderId, @Param(
        "clientOrderId") String clientOrderId, @Param("strategyId") long strategyId, @Param("instanceId") long instanceId, @Param("positionSide") String positionSide, @Param(
            "reduceOnly") boolean reduceOnly);

}
