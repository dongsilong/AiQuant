package org.dromara.quant.runtime.coordination.mapper;

import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;

/**
 * Atomic account/symbol/candle entry guard statements. The database's
 * UNIQUE(credential_id, symbol, kline_open_time) constraint is mandatory.
 * Keep conditional writes as single SQL statements, not read/modify/write.
 */
@Mapper
public interface StrategyEntryGuardMapper {

    int tryClaim(@Param("credentialId") Long credentialId, @Param("symbol") String symbol, @Param("instanceId") Long instanceId, @Param("openTime") LocalDateTime openTime);

    int blockClosedCandle(@Param("credentialId") Long credentialId, @Param("symbol") String symbol, @Param("instanceId") Long instanceId, @Param("openTime") LocalDateTime openTime);

    int bindOrder(@Param("instanceId") Long instanceId, @Param("credentialId") Long credentialId, @Param("symbol") String symbol, @Param("openTime") LocalDateTime openTime,
                  @Param("orderId") Long orderId);

    int release(@Param("instanceId") Long instanceId, @Param("credentialId") Long credentialId, @Param("symbol") String symbol, @Param("openTime") LocalDateTime openTime);
}
