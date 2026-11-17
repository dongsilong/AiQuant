package org.dromara.quant.exchange.binance;

import com.sun.net.httpserver.HttpServer;
import org.dromara.quant.config.QuantProperties;
import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.exchange.ExchangeTradingPort;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

@Tag("dev")
class BinanceGatewayProtectionOrderTest {
    @ParameterizedTest
    @ValueSource(ints = {400, 503})
    void onlyExplicitParameterRejectionCanReleaseUnknownProtection(int status) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            boolean time = exchange.getRequestURI().getPath().contains("time");
            String response = time ? "{\"serverTime\":" + System.currentTimeMillis() + "}" : "{\"code\":-4023,\"msg\":\"Qty not increased by step size.\"}";
            byte[] data = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(time ? 200 : status, data.length);
            exchange.getResponseBody().write(data);
            exchange.close();
        });
        server.start();
        try {
            var properties = new QuantProperties();
            properties.getBinance().setTestnetUrl("http://127.0.0.1:" + server.getAddress().getPort());
            var gateway = new BinanceGatewayAdapter(properties);
            var request = new ExchangeGateway.ProtectionOrderRequest(new ExchangeGateway.Credential("test", "test", "HMAC", "TESTNET"), "ONDOUSDT", "SELL", "BOTH", "STOP_MARKET"
                , new BigDecimal("5616.5"), new BigDecimal("0.4961"), "QFTP_test", true);
            var failure = assertThrows(ExchangeTradingPort.ExchangeRequestException.class, () -> gateway.placeProtectionOrder(request));
            assertEquals(status == 400, failure instanceof ExchangeTradingPort.OrderRejectedException);
            assertFalse(failure.getMessage().contains("signature="));
        } finally {
            server.stop(0);
        }
    }

    @org.junit.jupiter.api.Test
    void concurrentExactProtectionQueriesShareOneExchangeRequest() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var queryCount = new java.util.concurrent.atomic.AtomicInteger();
        var queryStarted = new java.util.concurrent.CountDownLatch(1);
        var releaseQuery = new java.util.concurrent.CountDownLatch(1);
        server.createContext("/", exchange -> {
            boolean time = exchange.getRequestURI().getPath().contains("/time");
            String response;
            if (time) {
                response = "{\"serverTime\":" + System.currentTimeMillis() + "}";
            } else {
                queryCount.incrementAndGet();
                queryStarted.countDown();
                try {
                    assertTrue(releaseQuery.await(3, java.util.concurrent.TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    exchange.sendResponseHeaders(500, -1);
                    exchange.close();
                    return;
                }
                response = "{\"algoId\":123456,\"clientAlgoId\":\"QHARD_test\",\"algoStatus\":\"NEW\","
                    + "\"symbol\":\"ONDOUSDT\",\"side\":\"SELL\",\"positionSide\":\"BOTH\","
                    + "\"orderType\":\"STOP_MARKET\",\"quantity\":\"1\",\"triggerPrice\":\"1\","
                    + "\"updateTime\":" + System.currentTimeMillis() + ",\"actualQty\":\"0\"}";
            }
            byte[] data = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, data.length);
            exchange.getResponseBody().write(data);
            exchange.close();
        });
        server.start();
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var properties = new QuantProperties();
            properties.getBinance().setTestnetUrl("http://127.0.0.1:" + server.getAddress().getPort());
            var gateway = new BinanceGatewayAdapter(properties);
            var credential = new ExchangeGateway.Credential("test", "test", "HMAC", "TESTNET");
            var first = executor.submit(() -> gateway.queryProtectionOrder(credential, 123456L, "QHARD_test", "ONDOUSDT"));
            assertTrue(queryStarted.await(3, java.util.concurrent.TimeUnit.SECONDS));
            var secondThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
            var secondStarted = new java.util.concurrent.CountDownLatch(1);
            var second = executor.submit(() -> {
                secondThread.set(Thread.currentThread());
                secondStarted.countDown();
                return gateway.queryProtectionOrder(credential, 123456L, "QHARD_test", "ONDOUSDT");
            });
            assertTrue(secondStarted.await(3, java.util.concurrent.TimeUnit.SECONDS));
            long waitDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            while (secondThread.get().getState() != Thread.State.WAITING && System.nanoTime() < waitDeadline) {
                Thread.yield();
            }
            assertEquals(Thread.State.WAITING, secondThread.get().getState());
            releaseQuery.countDown();

            assertEquals(123456L, first.get(5, java.util.concurrent.TimeUnit.SECONDS).orderId());
            assertEquals(123456L, second.get(5, java.util.concurrent.TimeUnit.SECONDS).orderId());
            assertEquals(1, queryCount.get());
        } finally {
            releaseQuery.countDown();
            executor.shutdownNow();
            server.stop(0);
        }
    }

    @org.junit.jupiter.api.Test
    void protectionOrderUsesContractPriceWorkingType() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var submittedRequest = new java.util.concurrent.atomic.AtomicReference<String>();
        server.createContext("/", exchange -> {
            String response;
            int status;
            if (exchange.getRequestURI().getPath().contains("time")) {
                response = "{\"serverTime\":" + System.currentTimeMillis() + "}";
                status = 200;
            } else {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                submittedRequest.set(exchange.getRequestURI().getRawQuery() + "&" + body);
                response = "{\"algoId\":123456,\"clientAlgoId\":\"QFTP_test\",\"algoStatus\":\"NEW\"}";
                status = 200;
            }
            byte[] data = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, data.length);
            exchange.getResponseBody().write(data);
            exchange.close();
        });
        server.start();
        try {
            var properties = new QuantProperties();
            properties.getBinance().setTestnetUrl("http://127.0.0.1:" + server.getAddress().getPort());
            var gateway = new BinanceGatewayAdapter(properties);
            var request = new ExchangeGateway.ProtectionOrderRequest(new ExchangeGateway.Credential("test", "test", "HMAC", "TESTNET"), "ONDOUSDT", "SELL", "BOTH", "STOP_MARKET",
                new BigDecimal("5616.5"), new BigDecimal("0.4961"), "QFTP_test", true);

            var result = gateway.placeProtectionOrder(request);

            assertEquals(123456L, result.orderId());
            assertEquals("QFTP_test", result.clientOrderId());
            assertNotNull(submittedRequest.get());
            assertTrue(submittedRequest.get().contains("workingType=CONTRACT_PRICE"), submittedRequest.get());
        } finally {
            server.stop(0);
        }
    }

    @org.junit.jupiter.api.Test
    void protectionOrderPassesMarkPriceWorkingType() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var submittedRequest = new java.util.concurrent.atomic.AtomicReference<String>();
        server.createContext("/", exchange -> {
            String response;
            if (exchange.getRequestURI().getPath().contains("time")) {
                response = "{\"serverTime\":" + System.currentTimeMillis() + "}";
            } else {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                submittedRequest.set(exchange.getRequestURI().getRawQuery() + "&" + body);
                response = "{\"algoId\":123456,\"clientAlgoId\":\"QFTP_test\",\"algoStatus\":\"NEW\"}";
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
            var request = new ExchangeGateway.ProtectionOrderRequest(new ExchangeGateway.Credential("test", "test", "HMAC", "TESTNET"), "ONDOUSDT", "SELL", "BOTH", "STOP_MARKET",
                new BigDecimal("5616.5"), new BigDecimal("0.4961"), "QFTP_test", true, "MARK_PRICE");

            gateway.placeProtectionOrder(request);

            assertNotNull(submittedRequest.get());
            assertTrue(submittedRequest.get().contains("workingType=MARK_PRICE"), submittedRequest.get());
        } finally {
            server.stop(0);
        }
    }
}
