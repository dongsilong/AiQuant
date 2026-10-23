package org.dromara.quant.reconcile.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDateTime;
import java.util.List;

/** 记录各账户交易对已经完整采集到的 Binance 成交历史边界。 */
@Mapper
public interface StrategyFillSyncCursorMapper {

    LocalDateTime selectLastCompleteTo(@Param("credentialId") Long credentialId, @Param("symbol") String symbol);

    List<String> selectSymbols(@Param("credentialId") Long credentialId);

    int insertCursor(@Param("credentialId") Long credentialId, @Param("symbol") String symbol, @Param("lastCompleteTo") LocalDateTime lastCompleteTo);
    int advanceIfBehind(@Param("credentialId") Long credentialId, @Param("symbol") String symbol, @Param("lastCompleteTo") LocalDateTime lastCompleteTo);

    /** 完整补采后只修复采集开始时读到的未来游标；并发变更不能被回退覆盖。 */
    int repairFuture(@Param("credentialId") Long credentialId, @Param("symbol") String symbol, @Param("expected") LocalDateTime expected,
                     @Param("lastCompleteTo") LocalDateTime lastCompleteTo);
}
