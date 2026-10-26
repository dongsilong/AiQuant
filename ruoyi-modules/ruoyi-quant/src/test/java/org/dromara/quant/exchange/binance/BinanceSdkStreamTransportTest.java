package org.dromara.quant.exchange.binance;

import com.binance.connector.client.common.websocket.adapter.stream.StreamConnectionInterface;
import com.binance.connector.client.common.websocket.configuration.WebSocketClientConfiguration;
import com.binance.connector.client.common.websocket.dtos.RequestWrapperDTO;
import com.binance.connector.client.common.websocket.service.StreamBlockingQueue;
import com.binance.connector.client.common.websocket.service.StreamBlockingQueueWrapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BinanceSdkStreamTransportTest {
    @Test
    void leavesMarketReconnectToTheHubBeforeSdkPeriodicRotation() {
        WebSocketClientConfiguration configuration = BinanceSdkStreamTransport.marketConfiguration("wss://example.test/stream", null);

        assertEquals(24 * 60 * 60 * 1000, configuration.getReconnectIntervalTime());
    }

    @Test
    void usesPublicConcreteJettyListenerType() {
        assertTrue(Modifier.isPublic(MarketStreamConnectionWrapper.class.getModifiers()));
    }

    @Test
    void exposesJettyCallbackBasedMarketCloseSignature() throws Exception {
        var close = MarketStreamConnectionWrapper.class.getMethod("onWebSocketClose", int.class, String.class,
            org.eclipse.jetty.websocket.api.Callback.class);
        assertTrue(Modifier.isPublic(close.getModifiers()));
    }

    @Test
    void notifiesTheMarketOwnerWhenSdkSocketClosesInsteadOfAllowingSdkReconnect() {
        AtomicBoolean disconnected = new AtomicBoolean();

        BinanceSdkStreamTransport.notifyMarketSocketClosed("test-connection", 1006, "Session Closed", () -> disconnected.set(true), "BTCUSDT", "1m");

        assertTrue(disconnected.get());
    }

    @Test
    void drainsSdkQueuesWithoutWaitingOnEmptyStreamsAndKeepsFairOrder() {
        BlockingQueue<String> trades = new LinkedBlockingQueue<>();
        BlockingQueue<String> marks = new LinkedBlockingQueue<>();
        BlockingQueue<String> klines = new LinkedBlockingQueue<>();
        trades.addAll(List.of("trade-1", "trade-2"));
        marks.add("mark-1");
        klines.add("kline-1");
        List<String> received = new ArrayList<>();

        int drained = BinanceSdkStreamTransport.drainAvailable(List.of(trades, marks, klines), received::add, 8);

        assertEquals(4, drained);
        assertEquals(List.of("trade-1", "mark-1", "kline-1", "trade-2"), received);
        assertEquals(0, BinanceSdkStreamTransport.drainAvailable(List.of(trades, marks, klines), received::add, 8));
    }

    @Test
    void connectsSdkBeforeCreatingStreamSubscriptions() {
        AtomicBoolean connected = new AtomicBoolean();
        List<String> calls = new ArrayList<>();
        StreamConnectionInterface connection = new StreamConnectionInterface() {
            @Override
            public Map<String, StreamBlockingQueue<String>> subscribe(RequestWrapperDTO<Set<String>, Object> request) {
                return Map.of();
            }

            @Override
            public void unsubscribe(StreamBlockingQueueWrapper queue) {
            }

            @Override
            public void unsubscribe(StreamBlockingQueue queue) {
            }

            @Override
            public void connect() {
                calls.add("connect");
                connected.set(true);
            }

            @Override
            public void disconnect() {
                connected.set(false);
            }

            @Override
            public boolean isConnected() {
                calls.add("isConnected");
                return connected.get();
            }

            @Override
            public void setUserAgent(String userAgent) {
            }

            @Override
            public void stop() {
            }
        };

        BinanceSdkStreamTransport.ensureConnected(connection);

        assertEquals(List.of("isConnected", "connect", "isConnected"), calls);
    }
}
