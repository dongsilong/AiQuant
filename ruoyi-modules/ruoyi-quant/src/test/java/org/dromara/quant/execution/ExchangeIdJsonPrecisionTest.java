package org.dromara.quant.execution;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.dromara.quant.exchange.ExchangeGateway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("dev")
class ExchangeIdJsonPrecisionTest {
    private final com.fasterxml.jackson.databind.ObjectMapper json = JsonMapper.builder().addModule(new JavaTimeModule()).build();

    @Test
    void restAndSseOrderIdsAreSerializedAsLosslessStrings() throws Exception {
        long unsafe = 9007199254740993L;
        var order = new ExchangeGateway.OrderSnapshot("BTCUSDT", unsafe, "cid", "BUY", "MARKET", "NEW", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, Instant.EPOCH);
        var trade = new ExchangeGateway.TradeSnapshot("BTCUSDT", unsafe, unsafe, "BUY", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, Instant.EPOCH);
        var algo = new ExchangeGateway.ProtectionOrderSnapshot("BTCUSDT", unsafe, "algo", "NEW", "SELL", "LONG", "STOP_MARKET", BigDecimal.ONE, BigDecimal.ONE, Instant.EPOCH);
        assertTrue(json.writeValueAsString(order).contains("\"orderId\":\"9007199254740993\""));
        String tradeJson = json.writeValueAsString(trade);
        assertTrue(tradeJson.contains("\"tradeId\":\"9007199254740993\""));
        assertTrue(tradeJson.contains("\"orderId\":\"9007199254740993\""));
        assertTrue(json.writeValueAsString(algo).contains("\"algoId\":\"9007199254740993\""));
        assertTrue(json.writeValueAsString(new ExecutionResult(true, "FILLED", unsafe, "cid")).contains("\"orderId\":\"9007199254740993\""));
    }
}
