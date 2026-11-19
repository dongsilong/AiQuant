package org.dromara.quant.account;

import org.dromara.quant.account.domain.vo.TradeCycleSourceFill;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeCycleAssemblerTest {

    @Test
    void hedgeLongConflictDoesNotBlockShortCycle() {
        TradeCycleAssembler.Result result = TradeCycleAssembler.assemble(List.of(
            fill(1L, 101L, "SELL", "LONG", "LONG", true, "1"),
            fill(2L, 102L, "SELL", "SHORT", "SHORT", false, "2"),
            fill(3L, 103L, "BUY", "SHORT", "SHORT", true, "2")
        ));

        assertEquals(1, result.incompleteCount());
        assertEquals(3, result.allocations().size());
        assertEquals("UNKNOWN", result.allocations().get(0).getFillRole());
        assertTrue(result.cycles().stream().anyMatch(cycle ->
            "SHORT".equals(cycle.getPositionSide()) && "CLOSED".equals(cycle.getStatus())
                && cycle.getFillCount() == 2));
        assertTrue(result.cycles().stream().anyMatch(cycle ->
            "LONG".equals(cycle.getPositionSide()) && "RECONCILE_REQUIRED".equals(cycle.getStatus())));
    }

    @Test
    void hedgeConflictBlocksOnlyTheSameDirection() {
        TradeCycleAssembler.Result result = TradeCycleAssembler.assemble(List.of(
            fill(11L, 111L, "SELL", "LONG", "LONG", true, "1"),
            fill(12L, 112L, "BUY", "LONG", "LONG", false, "1"),
            fill(13L, 113L, "SELL", "SHORT", "SHORT", false, "2"),
            fill(14L, 114L, "BUY", "SHORT", "SHORT", true, "2")
        ));

        assertEquals(3, result.allocations().size());
        assertFalse(result.allocations().stream().anyMatch(allocation ->
            allocation.getExchangeTradeId().equals(12L)));
        assertTrue(result.cycles().stream().anyMatch(cycle ->
            "SHORT".equals(cycle.getPositionSide()) && "CLOSED".equals(cycle.getStatus())));
    }

    @Test
    void hedgeShortConflictDoesNotBlockLongCycle() {
        TradeCycleAssembler.Result result = TradeCycleAssembler.assemble(List.of(
            fill(31L, 131L, "BUY", "SHORT", "SHORT", true, "1"),
            fill(32L, 132L, "BUY", "LONG", "LONG", false, "2"),
            fill(33L, 133L, "SELL", "LONG", "LONG", true, "2")
        ));

        assertEquals(3, result.allocations().size());
        assertTrue(result.cycles().stream().anyMatch(cycle ->
            "LONG".equals(cycle.getPositionSide()) && "CLOSED".equals(cycle.getStatus())
                && cycle.getFillCount() == 2));
    }

    @Test
    void oneWayConflictStillBlocksTheWholePositionStream() {
        TradeCycleAssembler.Result result = TradeCycleAssembler.assemble(List.of(
            fill(21L, 121L, "SELL", "LONG", "BOTH", true, "1"),
            fill(22L, 122L, "BUY", "LONG", "BOTH", false, "1")
        ));

        assertEquals(1, result.allocations().size());
        assertEquals("UNKNOWN", result.allocations().get(0).getFillRole());
    }

    private static TradeCycleSourceFill fill(long tradeId, long orderId, String side,
                                             String positionSide, String exchangePositionSide,
                                             boolean reduceOnly, String quantity) {
        TradeCycleSourceFill fill = new TradeCycleSourceFill();
        fill.setCredentialId(1L);
        fill.setSymbol("BTCUSDT");
        fill.setExchangeTradeId(tradeId);
        fill.setExchangeOrderId(orderId);
        fill.setSide(side);
        fill.setPositionSide(positionSide);
        fill.setExchangePositionSide(exchangePositionSide);
        fill.setReduceOnly(reduceOnly);
        fill.setClosePosition(false);
        fill.setOrderExecutedQuantity(new BigDecimal(quantity));
        fill.setQuantity(new BigDecimal(quantity));
        fill.setPrice(BigDecimal.TEN);
        fill.setCommission(BigDecimal.ZERO);
        fill.setCommissionAsset("USDT");
        fill.setRealizedPnl(BigDecimal.ZERO);
        fill.setTradeTime(LocalDateTime.of(2026, 11, 19, 10, 0).plusSeconds(tradeId));
        return fill;
    }
}
