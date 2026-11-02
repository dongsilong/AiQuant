package org.dromara.quant.execution;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 持久化隔离式策略持仓重建任务及其影子投影。 */
@Mapper
public interface StrategyPositionRebuildMapper {
    int insertRun(@Param("id") long id, @Param("credentialId") long credentialId, @Param("symbol") String symbol,
                  @Param("activeKey") String activeKey, @Param("historyFrom") LocalDateTime historyFrom,
                  @Param("historyTo") LocalDateTime historyTo, @Param("sourceMaxJournalId") long sourceMaxJournalId,
                  @Param("sourceFillCount") long sourceFillCount);

    Map<String, Object> selectRun(@Param("runId") long runId);

    Map<String, Object> lockRun(@Param("runId") long runId);

    int seedInventory(@Param("runId") long runId, @Param("credentialId") long credentialId, @Param("symbol") String symbol);

    Map<String, Object> lockInventory(@Param("runId") long runId, @Param("instanceId") long instanceId, @Param("side") String side);

    List<Map<String, Object>> selectInventoryBaselineForUpdate(@Param("runId") long runId);

    int insertInventory(@Param("runId") long runId, @Param("credentialId") long credentialId, @Param("symbol") String symbol,
                        @Param("instanceId") long instanceId, @Param("side") String side, @Param("strategyId") long strategyId);

    int updateInventory(@Param("runId") long runId, @Param("instanceId") long instanceId, @Param("side") String side,
                        @Param("strategyId") long strategyId, @Param("quantity") BigDecimal quantity, @Param("cost") BigDecimal cost,
                        @Param("cycleNumber") long cycleNumber, @Param("cycleId") Long cycleId,
                        @Param("tradeTime") LocalDateTime tradeTime, @Param("tradeId") long tradeId);

    int insertFill(@Param("runId") long runId, @Param("credentialId") long credentialId, @Param("symbol") String symbol,
                   @Param("strategyId") long strategyId, @Param("instanceId") long instanceId, @Param("cycleId") long cycleId,
                   @Param("tradeId") long tradeId, @Param("orderId") long orderId, @Param("side") String side,
                   @Param("role") String role, @Param("exchangeSide") String exchangeSide, @Param("quantity") BigDecimal quantity,
                   @Param("price") BigDecimal price, @Param("fee") BigDecimal fee, @Param("feeAsset") String feeAsset,
                   @Param("pnl") BigDecimal pnl, @Param("tradeTime") LocalDateTime tradeTime);

    List<Map<String, Object>> selectFillPage(@Param("runId") long runId, @Param("afterTradeTime") LocalDateTime afterTradeTime,
                                             @Param("afterTradeId") Long afterTradeId, @Param("limit") int limit);

    long countFills(@Param("runId") long runId);

    BigDecimal sumOpenQuantity(@Param("runId") long runId, @Param("side") String side);

    BigDecimal sumCostBasis(@Param("runId") long runId, @Param("side") String side);

    long countInvalidOpenInventory(@Param("runId") long runId);

    long countJournalAfterId(@Param("credentialId") long credentialId, @Param("symbol") String symbol, @Param("maxId") long maxId);

    int updateProgress(@Param("runId") long runId, @Param("processed") int processed,
                       @Param("cursorTradeTime") LocalDateTime cursorTradeTime, @Param("cursorTradeId") Long cursorTradeId);

    int markReady(@Param("runId") long runId);

    int markFailed(@Param("runId") long runId, @Param("reason") String reason);

    int promoteInventory(@Param("runId") long runId);

    int markVerified(@Param("runId") long runId, @Param("exchangeLong") BigDecimal exchangeLong,
                     @Param("exchangeShort") BigDecimal exchangeShort, @Param("rebuiltLong") BigDecimal rebuiltLong,
                     @Param("rebuiltShort") BigDecimal rebuiltShort,
                     @Param("exchangeLongCost") BigDecimal exchangeLongCost, @Param("exchangeShortCost") BigDecimal exchangeShortCost,
                     @Param("rebuiltLongCost") BigDecimal rebuiltLongCost, @Param("rebuiltShortCost") BigDecimal rebuiltShortCost);
}
