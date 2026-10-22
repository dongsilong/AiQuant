package org.dromara.quant.runtime.coordination.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.Map;

/**
 * Validates a strategy's persisted ownership of a planned LIVE reduction.
 * The strategy state is not interchangeable with a shared Binance account position.
 */
@Mapper
public interface StrategyCloseOwnershipMapper {

    Map<String, Object> pendingOwnedExit(@Param("instanceId") Long instanceId, @Param("credentialId") Long credentialId, @Param("strategyId") Long strategyId,
                                         @Param("symbol") String symbol);
}
