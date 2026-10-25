package org.dromara.quant.reconcile.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/** Durable monotonic watermark for complete Binance income-history intervals. */
@Mapper
public interface FinancialEventSyncCursorMapper {
    LocalDateTime selectLastCompleteTo(@Param("credentialId") Long credentialId);
    int insertCursor(@Param("credentialId") Long credentialId, @Param("lastCompleteTo") LocalDateTime lastCompleteTo);
    int advanceIfBehind(@Param("credentialId") Long credentialId, @Param("lastCompleteTo") LocalDateTime lastCompleteTo);
}
