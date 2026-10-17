package org.dromara.quant.exchange.binance;

import com.sun.net.httpserver.HttpServer;
import org.dromara.quant.config.QuantProperties;
import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.exchange.ExchangeTradingPort;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("dev")
class BinanceGatewayEntryDeadlineTest {
    @Test
    void signalExpiringDuringClockPreparationNeverSendsOrderHttp() throws Exception {
        var orders = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String response;
            if (exchange.getRequestURI().getPath().contains("time")) {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                response = "{\"serverTime\":" + System.currentTimeMillis() + "}";
            } else {
                orders.incrementAndGet();
                response = "{\"orderId\":1,\"clientOrderId\":\"deadline\",\"status\":\"FILLED\"}";
            }
            byte[] data = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, data.length);
            exchange.getResponseBody().write(data);
            exchange.close();
        });
        server.start();
        try {
            var properties = new QuantProperties();
            properties.getBinance().setTestnetUrl("http://127.0.0.1:" + server.getAddress().getPort());
            var gateway = new BinanceGatewayAdapter(properties);
            var request = new ExchangeGateway.OrderRequest(new ExchangeGateway.Credential("test", "test", "HMAC", "TESTNET"), "BTCUSDT", "BUY", "MARKET", BigDecimal.ONE, null,
                false, "BOTH", "deadline", true, Instant.now().plusMillis(100));
            assertThrows(ExchangeTradingPort.OrderNotSentException.class, () -> gateway.placeOrder(request));
            assertEquals(0, orders.get());
        } finally {
            server.stop(0);
        }
    }
}
