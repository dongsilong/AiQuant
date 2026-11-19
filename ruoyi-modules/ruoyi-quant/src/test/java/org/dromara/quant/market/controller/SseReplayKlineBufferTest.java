package org.dromara.quant.market.controller;

import org.dromara.quant.exchange.ExchangeGateway;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class SseReplayKlineBufferTest {
    @Test
    void emitsLatestEightReplayKlinesWhenKlinesBecomeReady() {
        SseReplayKlineBuffer buffer = new SseReplayKlineBuffer();
        List<Object> delivered = new ArrayList<>();
        for (int index = 0; index < 200; index++) {
            buffer.publish(kline(index), delivered::add, true);
        }
        assertEquals(0, delivered.size());

        buffer.publish(status("KLINE_READY"), delivered::add, true);

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> snapshot = assertInstanceOf(java.util.Map.class, delivered.get(0));
        assertEquals("KLINE_SNAPSHOT", snapshot.get("type"));
        assertEquals("BTCUSDT", snapshot.get("symbol"));
        assertEquals("1m", snapshot.get("interval"));
        List<ExchangeGateway.Kline> klines = (List<ExchangeGateway.Kline>) snapshot.get("klines");
        assertEquals(List.of(192, 193, 194, 195, 196, 197, 198, 199).stream().map(index -> kline(index).kline().openTime()).toList(),
            klines.stream().map(ExchangeGateway.Kline::openTime).toList());
        assertEquals("KLINE_READY", ((ExchangeGateway.MarketEvent) delivered.get(1)).type());
        assertEquals(2, delivered.size());
    }

    @Test
    void keepsFourReplayKlinesForOtherIntervals() {
        SseReplayKlineBuffer buffer = new SseReplayKlineBuffer();
        List<Object> delivered = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            buffer.publish(kline(index, "15m"), delivered::add, true);
        }

        buffer.publish(status("CONNECTED"), delivered::add, true);

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> snapshot = assertInstanceOf(java.util.Map.class, delivered.get(0));
        List<ExchangeGateway.Kline> klines = (List<ExchangeGateway.Kline>) snapshot.get("klines");
        assertEquals(List.of(16, 17, 18, 19).stream().map(index -> kline(index, "15m").kline().openTime()).toList(),
            klines.stream().map(ExchangeGateway.Kline::openTime).toList());
        assertEquals("CONNECTED", ((ExchangeGateway.MarketEvent) delivered.get(1)).type());
        assertEquals(2, delivered.size());
    }

    private ExchangeGateway.MarketEvent kline(int minute) {
        return kline(minute, "1m");
    }

    private ExchangeGateway.MarketEvent kline(int minute, String interval) {
        Instant open = Instant.parse("2026-10-17T00:00:00Z").plusSeconds(minute * 60L);
        ExchangeGateway.Kline kline = new ExchangeGateway.Kline(open, open.plusSeconds(59), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
            BigDecimal.ONE, true);
        return new ExchangeGateway.MarketEvent("KLINE", "BTCUSDT", interval, null, kline, open, null, open, true);
    }

    private ExchangeGateway.MarketEvent status(String type) {
        return new ExchangeGateway.MarketEvent(type, "BTCUSDT", "1m", null, null, Instant.now(), null, Instant.now(), false);
    }
}
