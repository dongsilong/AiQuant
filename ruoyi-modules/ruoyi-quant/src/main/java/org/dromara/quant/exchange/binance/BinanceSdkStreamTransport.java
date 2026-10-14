package org.dromara.quant.exchange.binance;

import com.binance.connector.client.common.websocket.configuration.WebSocketClientConfiguration;
import com.binance.connector.client.common.websocket.service.StreamBlockingQueueWrapper;
import com.binance.connector.client.derivatives_trading_usds_futures.websocket.stream.DerivativesTradingUsdsFuturesWebSocketStreamsUtil;
import com.binance.connector.client.derivatives_trading_usds_futures.websocket.stream.api.DerivativesTradingUsdsFuturesWebSocketStreams;
import com.binance.connector.client.derivatives_trading_usds_futures.websocket.stream.model.AggregateTradeStreamsRequest;
import com.binance.connector.client.derivatives_trading_usds_futures.websocket.stream.model.Interval;
import com.binance.connector.client.derivatives_trading_usds_futures.websocket.stream.model.KlineCandlestickStreamsRequest;
import com.binance.connector.client.derivatives_trading_usds_futures.websocket.stream.model.MarkPriceStreamRequest;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import org.dromara.quant.config.QuantProperties;
import org.eclipse.jetty.client.HttpProxy;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Official Binance SDK 12.0.3 WebSocket Streams transport.
 *
 * <p>SDK internals allocate unbounded queues. The consumer thread therefore monitors the
 * upstream queue depth, fails closed when overloaded and asks the owning hub to reconnect and
 * replay. This is a protective threshold, NOT a strict physical queue bound; use RAW_OKHTTP
 * when strict transport-level backpressure is required.
 */
@Slf4j
final class BinanceSdkStreamTransport implements AutoCloseable {
    private final DerivativesTradingUsdsFuturesWebSocketStreams client;
    private final List<StreamBlockingQueueWrapper<?>> queues;
    private final Consumer<String> events;
    private final Runnable disconnected;
    private final int backlogLimit;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean connected = new AtomicBoolean();
    private final Thread consumer;

    static BinanceSdkStreamTransport market(String url, String symbol, String interval,
                                             Consumer<String> events, Runnable disconnected, int backlogLimit, QuantProperties.Proxy proxy) {
        WebSocketClientConfiguration config = configuration(url, proxy);
        var client = new DerivativesTradingUsdsFuturesWebSocketStreams(config);
        try {
            var trades = client.aggregateTradeStreams(new AggregateTradeStreamsRequest().symbol(symbol));
            var marks = client.markPriceStream(new MarkPriceStreamRequest().symbol(symbol));
            var klines = client.klineCandlestickStreams(
                new KlineCandlestickStreamsRequest().symbol(symbol).interval(Interval.fromValue(interval)));
            return new BinanceSdkStreamTransport(client, List.of(trades, marks, klines),
                events, disconnected, backlogLimit);
        } catch (RuntimeException ex) {
            stop(client);
            throw ex;
        }
    }

    static BinanceSdkStreamTransport user(String url, String listenKey,
                                           Consumer<String> events, Runnable disconnected, int backlogLimit) {
        WebSocketClientConfiguration config = configuration(url);
        var client = new DerivativesTradingUsdsFuturesWebSocketStreams(config);
        try {
            var queue = client.userData(listenKey);
            return new BinanceSdkStreamTransport(client, List.of(queue), events, disconnected, backlogLimit);
        } catch (RuntimeException ex) {
            stop(client);
            throw ex;
        }
    }

    static WebSocketClientConfiguration configuration(String url, QuantProperties.Proxy proxy) {
        if (url == null || !url.startsWith("wss://") || !url.endsWith("/stream")) {
            throw new IllegalArgumentException("Binance SDK Streams 连接地址必须为 wss://.../stream");
        }
        var config = DerivativesTradingUsdsFuturesWebSocketStreamsUtil.getClientConfiguration();
        config.setUrl(url);
        config.setUsePool(false);
        if (proxy != null && proxy.getHost() != null && !proxy.getHost().isBlank()) {
            if (proxy.getPort() == null || proxy.getPort() < 1 || proxy.getPort() > 65535) {
                throw new IllegalArgumentException("Binance SDK WebSocket HTTP proxy port is invalid");
            }
            config.setWebSocketProxy(new HttpProxy(proxy.getHost().trim(), proxy.getPort()));
        } else if (proxy != null && proxy.getPort() != null) {
            throw new IllegalArgumentException("Binance SDK WebSocket HTTP proxy host is missing");
        }
        return config;
    }

    private BinanceSdkStreamTransport(DerivativesTradingUsdsFuturesWebSocketStreams client,
                                       List<StreamBlockingQueueWrapper<?>> queues,
                                       Consumer<String> events, Runnable disconnected, int backlogLimit) {
        this.client = client;
        this.queues = queues;
        this.events = events;
        this.disconnected = disconnected;
        this.backlogLimit = Math.max(64, backlogLimit);
        this.consumer = Thread.ofVirtual().name("binance-sdk-stream-ingress").start(this::consume);
    }

    boolean connected() {
        if (closed.get()) { return false; }
        // User-data streams may legitimately have no events for long periods.
        try {
            return connected.get() || client.getConnection().isConnected();
        } catch (RuntimeException ex) {
            return connected.get();
        }
    }

    private void consume() {
        try {
            while (!closed.get()) {
                boolean handled = false;
                for (var queue : queues) {
                    if (closed.get()) { break; }
                    var raw = queue.getInnerQueue();
                    // Fail before parsing the next message; no stale decision may be made from an overloaded queue.
                    if (raw.size() >= backlogLimit) {
                        log.error("Binance SDK stream backlog reached threshold {}, force reconnect and replay", backlogLimit);
                        fail();
                        return;
                    }
                    String event = raw.poll(50, TimeUnit.MILLISECONDS);
                    if (event != null) {
                        connected.set(true);
                        handled = true;
                        events.accept(event);
                    }
                }
                // The higher-level market/user-data hubs also implement idle/reconnect supervision.
                if (!handled && Thread.currentThread().isInterrupted()) { return; }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException ex) {
            log.warn("Binance SDK Streams subscription stopped: {}", ex.getClass().getSimpleName());
            fail();
        }
    }

    private void fail() {
        if (closed.compareAndSet(false, true)) {
            connected.set(false);
            stop(client);
            try { disconnected.run(); }
            catch (RuntimeException ex) { log.warn("Binance Streams disconnected callback failed"); }
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            connected.set(false);
            consumer.interrupt();
            stop(client);
        }
    }

    private static void stop(DerivativesTradingUsdsFuturesWebSocketStreams client) {
        try { client.stop(); }
        catch (Exception ex) { log.debug("Binance SDK stream close: {}", ex.getClass().getSimpleName()); }
    }
}
