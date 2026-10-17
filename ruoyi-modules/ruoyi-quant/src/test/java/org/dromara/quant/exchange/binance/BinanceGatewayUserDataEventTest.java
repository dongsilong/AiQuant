package org.dromara.quant.exchange.binance;

import org.dromara.quant.config.QuantProperties;
import org.dromara.quant.exchange.ExchangeGateway;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.Tag("dev")
class BinanceGatewayUserDataEventTest {
    private final BinanceGatewayAdapter adapter = new BinanceGatewayAdapter(new QuantProperties());

    @Test
    void orderEventRetainsRawExchangeFields() {
        String json = "{\"e\":\"ORDER_TRADE_UPDATE\",\"E\":1000,\"o\":{\"s\":\"PUMPUSDT\",\"X\":\"FILLED\"}}";
        ExchangeGateway.UserDataEvent event = ReflectionTestUtils.invokeMethod(adapter, "parseUserDataEvent", json);
        assertEquals("ORDER_TRADE_UPDATE", event.eventType());
        assertEquals(json, event.rawJson());
    }

    @Test
    void algoEventIsNotDiscardedByUnionDeserialization() {
        String json = "{\"e\":\"ALGO_UPDATE\",\"E\":1000,\"o\":{\"s\":\"PUMPUSDT\",\"X\":\"FINISHED\"}}";
        ExchangeGateway.UserDataEvent event = ReflectionTestUtils.invokeMethod(adapter, "parseUserDataEvent", json);
        assertEquals("ALGO_UPDATE", event.eventType());
        assertEquals(json, event.rawJson());
    }

    @Test
    void combinedMarketEventPreservesSequenceAndPrice() {
        ExchangeGateway.MarketEvent event = ReflectionTestUtils.invokeMethod(adapter, "parseMarketEvent", "{\"stream\":\"pumpusdt@aggTrade\",\"data\":{\"e\":\"aggTrade\"," +
            "\"s\":\"PUMPUSDT\",\"E\":1000,\"a\":42,\"p\":\"0.00412\"}}", "PUMPUSDT", "1m");
        assertEquals("TRADE", event.type());
        assertEquals(42L, event.sequenceId());
        assertEquals(new java.math.BigDecimal("0.00412"), event.price());
        assertFalse(event.replay());
    }

    @Test
    void rawKlineRetainsClosedCandleAndOhlc() {
        ExchangeGateway.MarketEvent event = ReflectionTestUtils.invokeMethod(adapter, "parseMarketEvent", "{\"e\":\"kline\",\"s\":\"BTCUSDT\",\"E\":1000," +
            "\"k\":{\"s\":\"BTCUSDT\",\"i\":\"1m\",\"t\":0,\"T\":59999,\"o\":\"100\",\"h\":\"110\",\"l\":\"90\",\"c\":\"105\",\"v\":\"2\",\"x\":true}}", "BTCUSDT", "1m");
        assertEquals("KLINE", event.type());
        assertTrue(event.kline().closed());
        assertEquals(new java.math.BigDecimal("110"), event.kline().high());
    }
}
