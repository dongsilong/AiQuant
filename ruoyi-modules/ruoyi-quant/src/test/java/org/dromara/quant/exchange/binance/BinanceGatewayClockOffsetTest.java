package org.dromara.quant.exchange.binance;

import com.sun.net.httpserver.HttpServer;
import org.dromara.quant.config.QuantProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@Tag("dev")
class BinanceGatewayClockOffsetTest {
    private HttpServer server(AtomicInteger calls, AtomicInteger status) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            try { Thread.sleep(300); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            String response = status.get() == 200 ? "{\"serverTime\":" + System.currentTimeMillis() + "}" : "{\"code\":-1000,\"msg\":\"test failure\"}";
            byte[] data = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), data.length);
            exchange.getResponseBody().write(data); exchange.close();
        });
        server.start(); return server;
    }
    private long clock(BinanceGatewayAdapter adapter, HttpServer server) {
        return ReflectionTestUtils.invokeMethod(adapter, "clockOffset", "http://127.0.0.1:" + server.getAddress().getPort());
    }
    @Test void requestBodyDetectionDoesNotRelyOnInternalOkHttpApi() {
        for (String method : new String[] {"POST", "PUT", "PATCH", "PROPPATCH", "REPORT"}) {
            assertTrue(BinanceGatewayAdapter.requiresRequestBody(method), method);
        }
        for (String method : new String[] {"GET", "DELETE", "HEAD"}) {
            assertFalse(BinanceGatewayAdapter.requiresRequestBody(method), method);
        }
        assertFalse(BinanceGatewayAdapter.requiresRequestBody(null));
    }

    @Test void concurrentRequestsShareOneServerTimeRefresh() throws Exception {
        var calls = new AtomicInteger(); var server = server(calls, new AtomicInteger(200));
        var adapter = new BinanceGatewayAdapter(new QuantProperties());
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = new ArrayList<Future<Long>>();
            for (int i = 0; i < 4; i++) results.add(executor.submit(() -> { start.await(); return clock(adapter, server); }));
            start.countDown();
            long first = results.getFirst().get(4, TimeUnit.SECONDS);
            for (var result : results) assertEquals(first, result.get(4, TimeUnit.SECONDS));
            assertEquals(first, clock(adapter, server));
            assertEquals(1, calls.get());
        } finally { server.stop(0); }
    }
    @Test void liveAndTestnetEndpointsHaveIndependentClockCaches() throws Exception {
        var liveCalls = new AtomicInteger(); var testCalls = new AtomicInteger();
        var live = server(liveCalls, new AtomicInteger(200)); var testnet = server(testCalls, new AtomicInteger(200));
        try {
            var adapter = new BinanceGatewayAdapter(new QuantProperties());
            clock(adapter, live); clock(adapter, testnet); clock(adapter, live); clock(adapter, testnet);
            assertEquals(1, liveCalls.get()); assertEquals(1, testCalls.get());
        } finally { live.stop(0); testnet.stop(0); }
    }
    @Test void failedRefreshIsNotCachedAndCanRecover() throws Exception {
        var calls = new AtomicInteger(); var status = new AtomicInteger(500); var server = server(calls, status);
        try {
            var adapter = new BinanceGatewayAdapter(new QuantProperties());
            assertThrows(RuntimeException.class, () -> clock(adapter, server));
            status.set(200); clock(adapter, server); clock(adapter, server);
            assertEquals(2, calls.get());
        } finally { server.stop(0); }
    }
}
