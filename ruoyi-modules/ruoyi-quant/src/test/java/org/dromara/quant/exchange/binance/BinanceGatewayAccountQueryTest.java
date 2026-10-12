package org.dromara.quant.exchange.binance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;

import org.dromara.quant.config.QuantProperties;
import org.dromara.quant.exchange.ExchangeGateway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@Tag("dev")
class BinanceGatewayAccountQueryTest {

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {-2013, -2015})
    void onlyMissingOrderIsReturnedAsAbsent(int code) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            boolean time = exchange.getRequestURI().getPath().endsWith("/time");
            byte[] body = (time ? "{\"serverTime\":" + System.currentTimeMillis() + "}"
                : "{\"code\":" + code + ",\"msg\":\"query failed\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(time ? 200 : 400, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            var properties = new QuantProperties();
            properties.getBinance().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            var adapter = new BinanceGatewayAdapter(properties);
            var credential = new ExchangeGateway.Credential("key", "secret", "HMAC", "LIVE");
            if (code == -2013) {
                org.junit.jupiter.api.Assertions.assertNull(adapter.queryOrder(credential, "BRUSDT", 55L, "entry"));
            } else {
                org.junit.jupiter.api.Assertions.assertThrows(org.dromara.quant.exchange.ExchangeTradingPort.ExchangeRequestException.class,
                    () -> adapter.queryOrder(credential, "BRUSDT", 55L, "entry"));
            }
        } finally { server.stop(0); }
    }

    @Test
    void orderQueriesPreserveFillAverageInsteadOfMarketOrderPriceZero() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String order = "{\"symbol\":\"BTCUSDT\",\"orderId\":88,\"clientOrderId\":\"client\",\"side\":\"BUY\","
                + "\"type\":\"MARKET\",\"status\":\"FILLED\",\"price\":\"0\",\"avgPrice\":\"101.25\","
                + "\"origQty\":\"2\",\"executedQty\":\"2\",\"updateTime\":" + System.currentTimeMillis() + "}";
            String response = path.endsWith("/time") ? "{\"serverTime\":" + System.currentTimeMillis() + "}"
                : path.endsWith("/order") ? order : "[" + order + "]";
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            var properties = new QuantProperties();
            properties.getBinance().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            var gateway = new BinanceGatewayAdapter(properties);
            var credential = new ExchangeGateway.Credential("test", "test", "HMAC", "LIVE");
            var order = gateway.queryOrder(credential, "BTCUSDT", 88L, "client");
            assertEquals(0, new BigDecimal("101.25").compareTo(order.averagePrice()));
            assertEquals(0, BigDecimal.ZERO.compareTo(order.price()));
            assertEquals(0, new BigDecimal("2").compareTo(order.executedQuantity()));
            var now = java.time.Instant.now();
            var history = gateway.queryOrderHistory(credential, "BTCUSDT", new ExchangeGateway.TimeRange(now.minusSeconds(60), now), 10L);
            assertEquals(0, new BigDecimal("101.25").compareTo(history.getFirst().averagePrice()));
            assertEquals(0, new BigDecimal("101.25").compareTo(gateway.queryOrders(credential, "BTCUSDT").getFirst().averagePrice()));
        } finally { server.stop(0); }
    }

    @Test
    void emptyPositionsDoNotRequestLeverageEndpoint() throws IOException {
        var paths = new java.util.concurrent.CopyOnWriteArrayList<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            paths.add(path);
            byte[] body = (path.endsWith("/time") ? "{\"serverTime\":" + System.currentTimeMillis() + "}" : "[]")
                .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var properties = new QuantProperties();
            properties.getBinance().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            assertTrue(new BinanceGatewayAdapter(properties).queryPositions(
                new ExchangeGateway.Credential("key", "secret", "HMAC", "LIVE"), "BTCUSDT").isEmpty());
            assertTrue(paths.contains("/fapi/v3/positionRisk"));
            assertTrue(!paths.contains("/fapi/v2/positionRisk"), "empty positions must not consume another REST slot");
        } finally { server.stop(0); }
    }

    @Test
    void queriesOnlyOpenOrdersForCurrentOrders() throws IOException {
        AtomicReference<String> orderPath = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    String path = exchange.getRequestURI().getPath();
                    if (path.endsWith("/openOrders") || path.endsWith("/allOrders")) {
                        orderPath.set(path);
                    }
                    byte[] response =
                            path.endsWith("/time")
                                    ? ("{\"serverTime\":" + System.currentTimeMillis() + "}")
                                            .getBytes(StandardCharsets.UTF_8)
                                    : "[]".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.start();
        try {
            QuantProperties properties = new QuantProperties();
            properties.getBinance().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());

            List<ExchangeGateway.OrderSnapshot> orders =
                    new BinanceGatewayAdapter(properties)
                            .queryOrders(
                                    new ExchangeGateway.Credential(
                                            "key", "secret", "HMAC", "LIVE"),
                                    null);

            assertTrue(orders.isEmpty());
            assertEquals("/fapi/v1/openOrders", orderPath.get());
        } finally {
            server.stop(0);
        }
    }
    @Test
    void queriesConditionalOrdersAndRetainsProtectionFlags() throws IOException {
        AtomicReference<String> requestedPath = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body;
            if (path.endsWith("/time")) {
                body = "{\"serverTime\":" + System.currentTimeMillis() + "}";
            } else {
                requestedPath.set(path);
                body = "[{\"algoId\":123,\"clientAlgoId\":\"protect\",\"symbol\":\"PUMPUSDT\",\"side\":\"SELL\",\"positionSide\":\"BOTH\",\"orderType\":\"STOP_MARKET\",\"algoStatus\":\"NEW\",\"quantity\":\"100\",\"triggerPrice\":\"1.8\",\"reduceOnly\":true,\"closePosition\":false,\"updateTime\":1000}]";
            }
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            QuantProperties properties = new QuantProperties();
            properties.getBinance().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            var orders = new BinanceGatewayAdapter(properties).queryProtectionOrders(
                new ExchangeGateway.Credential("key", "secret", "HMAC", "LIVE"), null);
            assertEquals("/fapi/v1/openAlgoOrders", requestedPath.get());
            assertEquals(1, orders.size());
            assertTrue(orders.getFirst().reduceOnly());
            assertEquals(new java.math.BigDecimal("1.8"), orders.getFirst().triggerPrice());
        } finally {
            server.stop(0);
        }
    }

}
