package org.dromara.quant.market.controller;

import org.dromara.quant.exchange.ExchangeGateway;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SseReplayKlineBufferTest {
    @Test
    void emitsOnlyLatestFourReplayKlinesWhenMarketBecomesReady() {
        SseReplayKlineBuffer buffer = new SseReplayKlineBuffer();
        List<ExchangeGateway.MarketEvent> delivered = new ArrayList<>();
        for (int index = 0; index < 200; index++) {
            buffer.publish(kline(index), delivered::add);
        }
        assertEquals(0, delivered.size());

        buffer.publish(status("CONNECTED"), delivered::add);

        assertEquals(List.of(196, 197, 198, 199).stream().map(index -> kline(index).kline().openTime()).toList(),
            delivered.subList(0, 4).stream().map(event -> event.kline().openTime()).toList());
        assertEquals("CONNECTED", delivered.get(4).type());
        assertEquals(5, delivered.size());
    }

    private ExchangeGateway.MarketEvent kline(int minute) {
        Instant open = Instant.parse("2026-10-17T00:00:00Z").plusSeconds(minute * 60L);
        ExchangeGateway.Kline kline = new ExchangeGateway.Kline(open, open.plusSeconds(59), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
            BigDecimal.ONE, true);
        return new ExchangeGateway.MarketEvent("KLINE", "BTCUSDT", "1m", null, kline, open, null, open, true);
    }

    private ExchangeGateway.MarketEvent status(String type) {
        return new ExchangeGateway.MarketEvent(type, "BTCUSDT", "1m", null, null, Instant.now(), null, Instant.now(), false);
    }
}
