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
    @Insert("""
        INSERT IGNORE INTO quant_strategy_entry_guard
            (credential_id, symbol, instance_id, kline_open_time, guard_type, created_at)
        VALUES (#{credentialId}, #{symbol}, #{instanceId}, #{openTime}, 'ENTRY', NOW())
        """)
    int tryClaim(@Param("credentialId") Long credentialId, @Param("symbol") String symbol, @Param("instanceId") Long instanceId, @Param("openTime") LocalDateTime openTime);

    @Insert("""
        INSERT INTO quant_strategy_entry_guard
            (credential_id, symbol, instance_id, kline_open_time, guard_type, created_at)
        VALUES (#{credentialId}, #{symbol}, #{instanceId}, #{openTime}, 'CLOSE', NOW())
        ON DUPLICATE KEY UPDATE guard_type = 'CLOSE', instance_id = VALUES(instance_id)
        """)
    int blockClosedCandle(@Param("credentialId") Long credentialId, @Param("symbol") String symbol, @Param("instanceId") Long instanceId, @Param("openTime") LocalDateTime openTime);

    @Update("""
        UPDATE quant_strategy_entry_guard SET order_id = #{orderId}
        WHERE instance_id = #{instanceId}
          AND credential_id = #{credentialId}
          AND symbol = #{symbol}
          AND kline_open_time = #{openTime}
          AND (order_id IS NULL OR order_id = #{orderId})
        """)
    int bindOrder(@Param("instanceId") Long instanceId, @Param("credentialId") Long credentialId, @Param("symbol") String symbol, @Param("openTime") LocalDateTime openTime,
                  @Param("orderId") Long orderId);

    @Delete("""
        DELETE FROM quant_strategy_entry_guard
        WHERE instance_id = #{instanceId}
          AND credential_id = #{credentialId}
          AND symbol = #{symbol}
          AND kline_open_time = #{openTime}
          AND guard_type = 'ENTRY'
          AND order_id IS NULL
        """)
    int release(@Param("instanceId") Long instanceId, @Param("credentialId") Long credentialId, @Param("symbol") String symbol, @Param("openTime") LocalDateTime openTime);
}
