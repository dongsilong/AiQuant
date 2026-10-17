package org.dromara.quant.exchange.binance;

import com.sun.net.httpserver.HttpServer;
import org.dromara.quant.config.QuantProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Tag("dev")
class BinanceGatewayMarketEnvironmentTest {
    @org.junit.jupiter.api.Test
    void marketRestTimeoutsAreLongerThanTradingRestTimeouts() {
        QuantProperties properties = new QuantProperties();
        var gateway = new BinanceGatewayAdapter(properties);
        var marketClient = gateway.marketRestClient(new com.binance.connector.client.common.ApiClient(gateway.restConfiguration("https://example.invalid")));
        var accountClient = gateway.accountRestClient(new com.binance.connector.client.common.ApiClient(gateway.restConfiguration("https://example.invalid")));
        var tradingClient = gateway.restClient(new com.binance.connector.client.common.ApiClient(gateway.restConfiguration("https://example.invalid")));

        assertEquals(15_000, marketClient.getHttpClient().readTimeoutMillis());
        assertEquals(20_000, marketClient.getHttpClient().callTimeoutMillis());
        assertEquals(15_000, accountClient.getHttpClient().readTimeoutMillis());
        assertEquals(20_000, accountClient.getHttpClient().callTimeoutMillis());
        assertEquals(3_000, tradingClient.getHttpClient().readTimeoutMillis());
        assertEquals(5_000, tradingClient.getHttpClient().callTimeoutMillis());
    }

    private HttpServer server(String price, AtomicInteger calls) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            String path = exchange.getRequestURI().getPath();
            String body = path.endsWith("/time") ? "{\"serverTime\":" + System.currentTimeMillis() + "}" : path.endsWith("/klines") ? "[[1000,\"" + price + "\",\"" + price +
                "\",\"" + price + "\",\"" + price + "\",\"1\",2000,\"1\",1,\"1\",\"1\",\"0\"]]" : path.endsWith("/aggTrades") ? "[{\"a\":7,\"p\":\"" + price + "\",\"q\":\"1\"," +
                "\"f\":1,\"l\":1,\"T\":1000,\"m\":false}]" : "{\"symbol\":\"BTCUSDT\",\"price\":\"" + price + "\",\"markPrice\":\"" + price + "\"}";
            byte[] data = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, data.length);
            exchange.getResponseBody().write(data);
            exchange.close();
        });
        server.start();
        return server;
    }

    @ParameterizedTest
    @ValueSource(strings = {"TESTNET", "LIVE", "PAPER"})
    void priceHistoryAndTradeReplayStayInRequestedEnvironment(String mode) throws Exception {
        AtomicInteger liveCalls = new AtomicInteger(), testCalls = new AtomicInteger();
        HttpServer live = server("100", liveCalls), test = server("200", testCalls);
        try {
            QuantProperties properties = new QuantProperties();
            properties.getBinance().setWebsocketTransport("RAW_OKHTTP");
            properties.getBinance().setBaseUrl("http://127.0.0.1:" + live.getAddress().getPort());
            properties.getBinance().setTestnetUrl("http://127.0.0.1:" + test.getAddress().getPort());
            var gateway = new BinanceGatewayAdapter(properties);
            BigDecimal expected = new BigDecimal("TESTNET".equals(mode) ? "200" : "100");
            var price = gateway.queryPrice(mode, "BTCUSDT");
            assertEquals(0, expected.compareTo(price.lastTradePrice()));
            assertEquals(0, expected.compareTo(price.markPrice()));
            var candles = gateway.queryKlinesFrom(mode, "BTCUSDT", "1m", null, 2);
            assertEquals(0, expected.compareTo(candles.getFirst().open()));
            var trades = gateway.queryAggregateTradesFrom(mode, "BTCUSDT", "1m", 7L, 2);
            assertEquals(0, expected.compareTo(trades.getFirst().price()));
            assertTrue(trades.getFirst().replay());
            assertEquals(0, "TESTNET".equals(mode) ? liveCalls.get() : testCalls.get(), "cross-environment HTTP request");
        } finally {
            live.stop(0);
            test.stop(0);
        }
    }

    @org.junit.jupiter.api.Test
    void testnetUserDataStreamUsesConfiguredTestnetRoute() throws Exception {
        var arrived = new java.util.concurrent.CountDownLatch(1);
        var requested = new java.util.concurrent.atomic.AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/private/ws/")) {
                requested.set(path);
                exchange.sendResponseHeaders(400, -1);
                exchange.close();
                arrived.countDown();
                return;
            }
            String body = path.endsWith("/time") ? "{\"serverTime\":" + System.currentTimeMillis() + "}" : "{\"listenKey\":\"local-test-listen-key\"}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            String host = "127.0.0.1:" + server.getAddress().getPort();
            QuantProperties properties = new QuantProperties();
            properties.getBinance().setWebsocketTransport("RAW_OKHTTP");
            properties.getBinance().setBaseUrl("http://127.0.0.1:1");
            properties.getBinance().setTestnetUrl("http://" + host);
            properties.getBinance().setTestnetUserDataStreamUrl("ws://" + host + "/private/ws/");
            var gateway = new BinanceGatewayAdapter(properties);
            var session = gateway.subscribeUserData(new org.dromara.quant.exchange.ExchangeGateway.Credential("test", "test", "HMAC", "TESTNET"), ignored -> {
            });
            try {
                assertTrue(arrived.await(2, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals("/private/ws/local-test-listen-key", requested.get());
            } finally {
                session.close();
            }
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"TESTNET", "LIVE"})
    void webSocketUsesRequestedEnvironmentAndLowercaseStreams(String mode) throws Exception {
        var requested = new java.util.concurrent.atomic.AtomicReference<String>();
        var arrived = new java.util.concurrent.CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requested.set(exchange.getRequestURI().toString());
            assertNull(exchange.getRequestHeaders().getFirst("X-MBX-APIKEY"));
            exchange.sendResponseHeaders(400, -1);
            exchange.close();
            arrived.countDown();
        });
        server.start();
        try {
            QuantProperties properties = new QuantProperties();
            properties.getBinance().setWebsocketTransport("RAW_OKHTTP");
            String url = "ws://127.0.0.1:" + server.getAddress().getPort();
            // 非目标环境不可达，若选错环境则本机服务收不到请求。
            properties.getBinance().setMarketStreamUrl("ws://127.0.0.1:1/live");
            properties.getBinance().setTestnetMarketStreamUrl("ws://127.0.0.1:1/testnet");
            if ("TESTNET".equals(mode)) {
                properties.getBinance().setTestnetMarketStreamUrl(url + "/testnet/stream");
            } else {
                properties.getBinance().setMarketStreamUrl(url + "/live/stream");
            }
            var gateway = new BinanceGatewayAdapter(properties);
            var session = gateway.subscribeMarket(mode, "BTCUSDT", "1m", ignored -> {
            });
            try {
                assertTrue(arrived.await(2, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(("TESTNET".equals(mode) ? "/testnet/stream" : "/live/stream") + "?streams=btcusdt@aggTrade/btcusdt@markPrice@1s/btcusdt@kline_1m", requested.get());
            } finally {
                session.close();
            }
        } finally {
            server.stop(0);
        }
    }
}
