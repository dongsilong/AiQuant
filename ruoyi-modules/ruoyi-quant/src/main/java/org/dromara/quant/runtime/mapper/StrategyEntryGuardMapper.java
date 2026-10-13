package org.dromara.quant.runtime.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 原子开仓信号占用。数据库唯一键必须是
 * (credential_id, symbol, kline_open_time)，用于跨策略实例防重复。
 * MyBatis 自定义 SQL 保留原 JdbcTemplate 的原子语义。
 */
@Mapper
public interface StrategyEntryGuardMapper {
    @Insert("""
        INSERT IGNORE INTO quant_strategy_entry_guard
            (credential_id, symbol, instance_id, kline_open_time, created_at)
        VALUES (#{credentialId}, #{symbol}, #{instanceId}, #{klineOpenTime}, NOW())
        """)
    int tryClaim(@Param("instanceId") Long instanceId,
                 @Param("credentialId") Long credentialId,
                 @Param("symbol") String symbol,
                 @Param("klineOpenTime") LocalDateTime klineOpenTime);

    @Update("""
        UPDATE quant_strategy_entry_guard SET order_id=#{orderId}
        WHERE instance_id=#{instanceId} AND credential_id=#{credentialId}
          AND symbol=#{symbol} AND kline_open_time=#{klineOpenTime}
          AND (order_id IS NULL OR order_id=#{orderId})
        """)
    int bindOrder(@Param("instanceId") Long instanceId,
                  @Param("credentialId") Long credentialId,
                  @Param("symbol") String symbol,
                  @Param("klineOpenTime") LocalDateTime klineOpenTime,
                  @Param("orderId") Long orderId);

    @Delete("""
        DELETE FROM quant_strategy_entry_guard
        WHERE instance_id=#{instanceId} AND credential_id=#{credentialId}
          AND symbol=#{symbol} AND kline_open_time=#{klineOpenTime}
          AND order_id IS NULL
        """)
    int releaseUnbound(@Param("instanceId") Long instanceId,
                       @Param("credentialId") Long credentialId,
                       @Param("symbol") String symbol,
                       @Param("klineOpenTime") LocalDateTime klineOpenTime);
}
