package org.dromara.quant.runtime.coordination.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

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
            (credential_id, symbol, instance_id, kline_open_time, created_at)
        VALUES (#{credentialId}, #{symbol}, #{instanceId}, #{openTime}, NOW())
        """)
    int tryClaim(@Param("credentialId") Long credentialId,
                 @Param("symbol") String symbol,
                 @Param("instanceId") Long instanceId,
                 @Param("openTime") LocalDateTime openTime);

    @Update("""
        UPDATE quant_strategy_entry_guard SET order_id = #{orderId}
        WHERE instance_id = #{instanceId}
          AND credential_id = #{credentialId}
          AND symbol = #{symbol}
          AND kline_open_time = #{openTime}
          AND (order_id IS NULL OR order_id = #{orderId})
        """)
    int bindOrder(@Param("instanceId") Long instanceId,
                  @Param("credentialId") Long credentialId,
                  @Param("symbol") String symbol,
                  @Param("openTime") LocalDateTime openTime,
                  @Param("orderId") Long orderId);

    @Delete("""
        DELETE FROM quant_strategy_entry_guard
        WHERE instance_id = #{instanceId}
          AND credential_id = #{credentialId}
          AND symbol = #{symbol}
          AND kline_open_time = #{openTime}
          AND order_id IS NULL
        """)
    int release(@Param("instanceId") Long instanceId,
                @Param("credentialId") Long credentialId,
                @Param("symbol") String symbol,
                @Param("openTime") LocalDateTime openTime);
}
