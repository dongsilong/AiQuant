package org.dromara.quant.account;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Tag("dev")
class TradeHistoryAssemblerTest {
    private TradeHistoryAssembler.Fill fill(long id, long account, String symbol, String side, String position, String operation, String qty, String price, String fee,
                                            String pnl) {
        return new TradeHistoryAssembler.Fill(id, account, symbol, side, position, operation, null, new BigDecimal(qty), new BigDecimal(price), fee == null ? null :
            new BigDecimal(fee), pnl == null ? null : new BigDecimal(pnl), LocalDateTime.of(2026, 10, 9, 1, 0).plusSeconds(id));
    }

    @Test
    void aggregatesPartialFillsAndPartialClosesUsingWeightedPrices() {
        var result = TradeHistoryAssembler.assemble(List.of(fill(1, 10, "BTCUSDT", "BUY", "LONG", "OPEN", "1", "100", "0.1", "0"), fill(2, 10, "BTCUSDT", "BUY", "LONG", "OPEN",
            "3", "120", "0.3", "0"), fill(3, 10, "BTCUSDT", "SELL", "LONG", "CLOSE", "1", "130", "0.1", "15"), fill(4, 10, "BTCUSDT", "SELL", "LONG", "CLOSE", "3", "140", "0.3",
            "75")), Map.of(10L, "模拟1"));
        assertEquals(1, result.rows().size());
        var row = result.rows().getFirst();
        assertEquals("115", row.get("entryPrice"));
        assertEquals("137.5", row.get("closePrice"));
        assertEquals("90", row.get("realizedPnl"));
        assertEquals("0.8", row.get("commission"));
        assertEquals("模拟1", row.get("accountName"));
        assertEquals("CLOSED", row.get("status"));
        assertNotNull(row.get("closedAt"));
        assertNull(row.get("openingMargin"));
        assertNull(row.get("leverage"));
    }

    @Test
    void separatesAccountsSymbolsDirectionsAndRepeatedCycles() {
        var result = TradeHistoryAssembler.assemble(List.of(fill(1, 10, "BTCUSDT", "BUY", "LONG", "OPEN", "1", "100", "0", "0"), fill(2, 10, "BTCUSDT", "SELL", "LONG", "CLOSE",
            "1", "110", "0", "10"), fill(3, 10, "BTCUSDT", "BUY", "LONG", "OPEN", "1", "120", "0", "0"), fill(4, 11, "BTCUSDT", "BUY", "LONG", "OPEN", "1", "100", "0", "0"),
            fill(5, 10, "PUMPUSDT", "BUY", "LONG", "OPEN", "1", "1", "0", "0"), fill(6, 10, "BTCUSDT", "SELL", "SHORT", "OPEN", "1", "100", "0", "0")), Map.of());
        assertEquals(5, result.rows().size());
        assertEquals(4, result.rows().stream().filter(r -> "OPEN".equals(r.get("status"))).count());
        result.rows().stream().filter(r -> "OPEN".equals(r.get("status"))).forEach(r -> {
            assertNull(r.get("closePrice"));
            assertNull(r.get("closedAt"));
        });
    }

    @Test
    void excludesIncompleteChainsRatherThanInventingAnOpening() {
        var result = TradeHistoryAssembler.assemble(List.of(fill(1, 10, "BTCUSDT", "SELL", "LONG", "CLOSE", "1", "100", "0", "0"), fill(2, 10, "PUMPUSDT", "BUY", null, null, "1"
            , "1", "0", "0")), Map.of());
        assertTrue(result.rows().isEmpty());
        assertEquals(2, result.incompleteCount());
    }

    @Test
    void excludesOverCloseAndDoesNotTreatMissingFeesAsZero() {
        var result = TradeHistoryAssembler.assemble(List.of(fill(1, 10, "BTCUSDT", "BUY", "LONG", "OPEN", "1", "100", "0", "0"), fill(2, 10, "BTCUSDT", "SELL", "LONG", "CLOSE",
            "2", "110", "0", "0"), fill(3, 10, "PUMPUSDT", "BUY", "LONG", "OPEN", "1", "1", null, "0")), Map.of());
        assertEquals(1, result.rows().size());
        assertNull(result.rows().getFirst().get("commission"));
        assertEquals(1, result.incompleteCount());
    }

    @Test
    void identifiesOneWayShortFromConfirmedOperation() {
        var result = TradeHistoryAssembler.assemble(List.of(fill(1, 10, "BTCUSDT", "SELL", "BOTH", "OPEN", "2", "100", "0", "0"), fill(2, 10, "BTCUSDT", "BUY", "BOTH", "CLOSE",
            "2", "90", "0", "20")), Map.of());
        assertEquals("SHORT", result.rows().getFirst().get("direction"));
        assertEquals("20", result.rows().getFirst().get("realizedPnl"));
    }
}
