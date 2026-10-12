package org.dromara.quant.strategy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.Tag("dev")
class RealtimeKlineMomentumStrategyTest {
    private final RealtimeKlineMomentumStrategy strategy = new RealtimeKlineMomentumStrategy();

    @Test
    void opensLongWhenRealtimePriceBreaksUp() {
        StrategyContext context = new StrategyContext("BTCUSDT", null, null,
            new BigDecimal("100.30"), new BigDecimal("100.00"), Instant.now(), false, Instant.now(), BigDecimal.ZERO, BigDecimal.ZERO);
        var signal = strategy.evaluateEntry(context, Map.of("entryChangeRatio", new BigDecimal("0.002")));
        assertTrue(signal.isPresent());
        assertEquals(EntrySignal.Direction.LONG, signal.get().direction());
    }

    @Test
    void opensShortWhenRealtimePriceBreaksDown() {
        StrategyContext context = new StrategyContext("BTCUSDT", null, null,
            new BigDecimal("99.70"), new BigDecimal("100.00"), Instant.now(), false, Instant.now(), BigDecimal.ZERO, BigDecimal.ZERO);
        var signal = strategy.evaluateEntry(context, Map.of("entryChangeRatio", new BigDecimal("0.002")));
        assertTrue(signal.isPresent());
        assertEquals(EntrySignal.Direction.SHORT, signal.get().direction());
    }

    @Test
    void closesOnTakeProfit() {
        StrategyContext context = new StrategyContext("BTCUSDT", "LONG", new BigDecimal("100.00"),
            new BigDecimal("100.60"), new BigDecimal("100.00"), Instant.now(), false, Instant.now(), new BigDecimal("1"), new BigDecimal("10"));
        List<ExitSignal> exits = strategy.evaluateExit(context, Map.of("takeProfitRatio", new BigDecimal("0.005")));
        assertEquals(1, exits.size());
        assertEquals("TAKE_PROFIT", exits.get(0).type());
    }

    @Test
    void closesOnStopLossForShort() {
        StrategyContext context = new StrategyContext("BTCUSDT", "SHORT", new BigDecimal("100.00"),
            new BigDecimal("100.40"), new BigDecimal("100.00"), Instant.now(), false, Instant.now(), new BigDecimal("1"), new BigDecimal("10"));
        List<ExitSignal> exits = strategy.evaluateExit(context, Map.of("stopLossRatio", new BigDecimal("0.003")));
        assertEquals(1, exits.size());
        assertEquals("STOP_LOSS", exits.get(0).type());
    }
    @Test
    void exchangeMirrorUsesSameRawPriceThresholdsAsLocalExit() {
        StrategyContext c = new StrategyContext("BTCUSDT", "SHORT", new BigDecimal("100"), new BigDecimal("99"), new BigDecimal("100"), Instant.now(), false, Instant.now(), BigDecimal.ONE, BigDecimal.TEN);
        var protections = strategy.evaluateProtection(c, Map.of("takeProfitRatio", "0.05", "stopLossRatio", "0.02"));
        assertEquals(2, protections.size());
        var stop = protections.stream().filter(signal -> signal.type().equals("LOCAL_STOP")).findFirst().orElseThrow();
        var profit = protections.stream().filter(signal -> signal.type().equals("FIRST_TP")).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("102").compareTo(stop.triggerPrice()));
        assertEquals(0, new BigDecimal("95").compareTo(profit.triggerPrice()));
    }
}
