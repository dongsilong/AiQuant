package org.dromara.quant.execution.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.dromara.quant.execution.StrategyFillJournalRecord;

import java.util.List;

/** 持久化不可变交易所成交事实及其策略归属处理状态。 */
@Mapper
public interface StrategyFillJournalMapper {
    @Options(timeout = 3)
    @Select("""
        SELECT * FROM quant_strategy_fill_journal
        WHERE credential_id=#{credentialId} AND symbol=#{symbol} AND exchange_trade_id=#{tradeId}
        """)
    @Results(id = "strategyFillJournal", value = {
        @Result(id = true, property = "id", column = "id"),
        @Result(property = "credentialId", column = "credential_id"),
        @Result(property = "symbol", column = "symbol"),
        @Result(property = "exchangeTradeId", column = "exchange_trade_id"),
        @Result(property = "exchangeOrderId", column = "exchange_order_id"),
        @Result(property = "side", column = "side"),
        @Result(property = "quantity", column = "quantity"),
        @Result(property = "price", column = "price"),
        @Result(property = "commission", column = "commission"),
        @Result(property = "commissionAsset", column = "commission_asset"),
        @Result(property = "realizedPnl", column = "realized_pnl"),
        @Result(property = "tradeTime", column = "trade_time"),
        @Result(property = "attributionStatus", column = "attribution_status"),
        @Result(property = "statusReason", column = "status_reason"),
        @Result(property = "attemptCount", column = "attempt_count"),
        @Result(property = "lastAttemptAt", column = "last_attempt_at"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedAt", column = "updated_at")
    })
    StrategyFillJournalRecord selectByKey(@Param("credentialId") Long credentialId, @Param("symbol") String symbol, @Param("tradeId") Long tradeId);

    @Options(timeout = 3)
    @Insert("""
        INSERT INTO quant_strategy_fill_journal
          (id,credential_id,symbol,exchange_trade_id,exchange_order_id,side,quantity,price,commission,commission_asset,realized_pnl,trade_time,attribution_status,attempt_count,created_at,updated_at)
        VALUES
          (#{id},#{credentialId},#{symbol},#{exchangeTradeId},#{exchangeOrderId},#{side},#{quantity},#{price},#{commission},#{commissionAsset},#{realizedPnl},#{tradeTime},'CAPTURED',0,NOW(3),NOW(3))
        """)
    int insertRaw(StrategyFillJournalRecord record);

    @Options(timeout = 3)
    @Update("""
        UPDATE quant_strategy_fill_journal
        SET attribution_status=#{status}, status_reason=#{reason}, attempt_count=attempt_count+1,
            last_attempt_at=NOW(3), updated_at=NOW(3)
        WHERE id=#{id}
        """)
    int updateStatus(@Param("id") Long id, @Param("status") String status, @Param("reason") String reason);

    @Options(timeout = 3)
    @Select("""
        SELECT * FROM quant_strategy_fill_journal
        WHERE credential_id=#{credentialId} AND attribution_status IN ('CAPTURED','WAITING_ORDER')
        ORDER BY last_attempt_at, id
        LIMIT #{limit}
        """)
    @ResultMap("strategyFillJournal")
    List<StrategyFillJournalRecord> selectPending(@Param("credentialId") Long credentialId, @Param("limit") int limit);

    @Options(timeout = 3)
    @Select("""
        <script>
        SELECT * FROM quant_strategy_fill_journal
        WHERE credential_id=#{credentialId} AND attribution_status&lt;&gt;'ATTRIBUTED'
        <if test="symbol != null and symbol != ''">AND symbol=#{symbol}</if>
        ORDER BY trade_time DESC, id DESC
        LIMIT #{limit}
        </script>
        """)
    @ResultMap("strategyFillJournal")
    List<StrategyFillJournalRecord> selectUnresolved(@Param("credentialId") Long credentialId, @Param("symbol") String symbol, @Param("limit") int limit);

    @Options(timeout = 3)
    @Select("""
        SELECT COUNT(*) FROM quant_strategy_fill_journal
        WHERE credential_id=#{credentialId} AND attribution_status!='ATTRIBUTED'
        """)
    long countUnresolved(@Param("credentialId") Long credentialId);
}
