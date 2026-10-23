package org.dromara.quant.execution.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.dromara.quant.execution.StrategyFillJournalRecord;

import java.util.List;

/** 持久化不可变交易所成交事实及其策略归属处理状态。 */
@Mapper
public interface StrategyFillJournalMapper {

    StrategyFillJournalRecord selectByKey(@Param("credentialId") Long credentialId, @Param("symbol") String symbol, @Param("tradeId") Long tradeId);

    int insertRaw(StrategyFillJournalRecord record);

    int updateStatus(@Param("id") Long id, @Param("status") String status, @Param("reason") String reason);

    List<StrategyFillJournalRecord> selectPending(@Param("credentialId") Long credentialId, @Param("limit") int limit);

    List<StrategyFillJournalRecord> selectUnresolved(@Param("credentialId") Long credentialId, @Param("symbol") String symbol, @Param("limit") int limit);

    long countUnresolved(@Param("credentialId") Long credentialId);
}
