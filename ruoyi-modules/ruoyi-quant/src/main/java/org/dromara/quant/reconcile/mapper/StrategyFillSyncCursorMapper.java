package org.dromara.quant.reconcile.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.time.LocalDateTime;
import java.util.List;

/** 记录各账户交易对已经完整采集到的 Binance 成交历史边界。 */
@Mapper
public interface StrategyFillSyncCursorMapper {
    @Options(timeout = 3)
    @Select("""
        SELECT last_complete_to FROM quant_strategy_fill_sync_cursor
        WHERE credential_id=#{credentialId} AND symbol=#{symbol}
        """)
    LocalDateTime selectLastCompleteTo(@Param("credentialId") Long credentialId, @Param("symbol") String symbol);

    @Options(timeout = 3)
    @Select("""
        SELECT symbol FROM quant_strategy_fill_sync_cursor
        WHERE credential_id=#{credentialId}
        ORDER BY symbol
        """)
    List<String> selectSymbols(@Param("credentialId") Long credentialId);

    @Options(timeout = 3)
    @Insert("""
        INSERT INTO quant_strategy_fill_sync_cursor
          (credential_id,symbol,last_complete_to,updated_at)
        VALUES (#{credentialId},#{symbol},#{lastCompleteTo},NOW(3))
        """)
    int insertCursor(@Param("credentialId") Long credentialId, @Param("symbol") String symbol, @Param("lastCompleteTo") LocalDateTime lastCompleteTo);

    @Options(timeout = 3)
    @org.apache.ibatis.annotations.Update("""
        UPDATE quant_strategy_fill_sync_cursor
        SET last_complete_to=#{lastCompleteTo}, updated_at=NOW(3)
        WHERE credential_id=#{credentialId} AND symbol=#{symbol} AND last_complete_to<#{lastCompleteTo}
        """)
    int advanceIfBehind(@Param("credentialId") Long credentialId, @Param("symbol") String symbol, @Param("lastCompleteTo") LocalDateTime lastCompleteTo);

    /** 完整补采后只修复采集开始时读到的未来游标；并发变更不能被回退覆盖。 */
    @Options(timeout = 3)
    @org.apache.ibatis.annotations.Update("""
        UPDATE quant_strategy_fill_sync_cursor
        SET last_complete_to=#{lastCompleteTo}, updated_at=NOW(3)
        WHERE credential_id=#{credentialId} AND symbol=#{symbol}
          AND last_complete_to=#{expected} AND last_complete_to>#{lastCompleteTo}
        """)
    int repairFuture(@Param("credentialId") Long credentialId, @Param("symbol") String symbol, @Param("expected") LocalDateTime expected,
                     @Param("lastCompleteTo") LocalDateTime lastCompleteTo);
}
