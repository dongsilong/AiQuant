package org.dromara.quant.exchange.binance;

import com.binance.connector.client.common.websocket.adapter.stream.StreamConnectionInterface;
import com.binance.connector.client.common.websocket.adapter.stream.StreamConnectionWrapper;
import com.binance.connector.client.common.websocket.configuration.WebSocketClientConfiguration;
import com.binance.connector.client.common.websocket.dtos.RequestWrapperDTO;
import com.binance.connector.client.common.websocket.service.StreamBlockingQueue;
import com.binance.connector.client.common.websocket.service.StreamBlockingQueueWrapper;
import com.binance.connector.client.derivatives_trading_usds_futures.websocket.stream.DerivativesTradingUsdsFuturesWebSocketStreamsUtil;
import com.binance.connector.client.derivatives_trading_usds_futures.websocket.stream.JSON;
import com.binance.connector.client.derivatives_trading_usds_futures.websocket.stream.api.DerivativesTradingUsdsFuturesWebSocketStreams;
import com.binance.connector.client.derivatives_trading_usds_futures.websocket.stream.api.MarketApi;
import com.binance.connector.client.derivatives_trading_usds_futures.websocket.stream.model.AggregateTradeStreamsRequest;
import com.binance.connector.client.derivatives_trading_usds_futures.websocket.stream.model.Interval;
import com.binance.connector.client.derivatives_trading_usds_futures.websocket.stream.model.KlineCandlestickStreamsRequest;
import lombok.extern.slf4j.Slf4j;
import org.dromara.quant.config.QuantProperties;
import org.eclipse.jetty.client.HttpProxy;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Official Binance SDK WebSocket Streams transport (version pinned in Maven).
 *
 * <p>SDK internals allocate unbounded queues. The consumer thread therefore monitors the
 * upstream queue depth, fails closed when overloaded and asks the owning hub to reconnect and
 * replay. This is a protective threshold, NOT a strict physical queue bound; use RAW_OKHTTP
 * when strict transport-level backpressure is required.
 */
@Slf4j
final class BinanceSdkStreamTransport implements AutoCloseable {
    private final StreamConnectionInterface connection;
    private final List<? extends BlockingQueue<String>> rawQueues;
    private final Consumer<String> events;
    private final Runnable disconnected;
    private final int backlogLimit;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean connected = new AtomicBoolean();
    private final Thread consumer;

    private BinanceSdkStreamTransport(StreamConnectionInterface connection, List<? extends BlockingQueue<String>> rawQueues, Consumer<String> events, Runnable disconnected,
                                      int backlogLimit) {
        this.connection = connection;
        this.rawQueues = List.copyOf(rawQueues);
        this.events = events;
        this.disconnected = disconnected;
        this.backlogLimit = Math.max(64, backlogLimit);
        this.consumer = Thread.ofVirtual().name("binance-sdk-stream-ingress").start(this::consume);
    }

    static BinanceSdkStreamTransport market(String url, String symbol, String interval, Consumer<String> events, Runnable disconnected, int backlogLimit,
                                            QuantProperties.Proxy proxy) {
        WebSocketClientConfiguration config = marketConfiguration(url, proxy);
        // 与 SDK getMarketApi() 保持相同路由；先持有连接，再让 MarketApi 执行握手。
        // 否则构造 MarketApi 时握手失败，连接无法返回给调用方，其 Jetty 客户端和重连定时器会泄漏。
        if (!config.getUrl().endsWith("/market/stream")) {
            config.setUrl(config.getUrl().replace("/stream", "/market/stream"));
        }
        StreamConnectionWrapper connection = new MarketStreamConnectionWrapper(config, JSON.getGson(), disconnected, symbol, interval);
        return market(connection, symbol, interval, events, disconnected, backlogLimit);
    }

    static void notifyMarketSocketClosed(int statusCode, String reason, Runnable disconnected, String symbol, String interval) {
        log.warn("Binance SDK 行情 WebSocket 已关闭 symbol={} interval={} closeCode={} reason={}", symbol, interval, statusCode, reason);
        disconnected.run();
    }

    static WebSocketClientConfiguration marketConfiguration(String url, QuantProperties.Proxy proxy) {
        WebSocketClientConfiguration config = configuration(url, proxy);
        // MarketStreamHub rotates market connections before 24 hours and owns recovery/replay.
        config.setReconnectIntervalTime((int) TimeUnit.HOURS.toMillis(24));
        return config;
    }

    static BinanceSdkStreamTransport market(StreamConnectionInterface connection, String symbol, String interval,
                                            Consumer<String> events, Runnable disconnected, int backlogLimit) {
        try {
            var api = new MarketApi(connection);
            ensureConnected(connection);
            var trades = api.aggregateTradeStreams(new AggregateTradeStreamsRequest().symbol(symbol));
            var marks = subscribeMarkPriceAtOneSecond(connection, symbol);
            var klines = api.klineCandlestickStreams(new KlineCandlestickStreamsRequest().symbol(symbol).interval(Interval.fromValue(interval)));
            return new BinanceSdkStreamTransport(connection, List.of(trades.getInnerQueue(), marks, klines.getInnerQueue()), events, disconnected, backlogLimit);
        } catch (RuntimeException ex) {
            // 未连通也必须 stop，SDK stop() 会禁止重连、取消定时器并释放客户端线程。
            stop(connection);
            throw ex;
        }
    }

    static BinanceSdkStreamTransport user(String url, String listenKey, Consumer<String> events, Runnable disconnected, int backlogLimit, QuantProperties.Proxy proxy) {
        WebSocketClientConfiguration config = configuration(url, proxy);
        var client = new DerivativesTradingUsdsFuturesWebSocketStreams(config);
        StreamConnectionInterface connection = null;
        try {
            connection = client.getConnection();
            ensureConnected(connection);
            var queue = client.userData(listenKey);
            return new BinanceSdkStreamTransport(connection, List.of(queue.getInnerQueue()), events, disconnected, backlogLimit);
        } catch (RuntimeException ex) {
            if (connection != null) {
                stop(connection);
            }
            throw ex;
        }
    }

    private static StreamBlockingQueue<String> subscribeMarkPriceAtOneSecond(StreamConnectionInterface connection, String symbol) {
        String stream = symbol + "@markPrice@1s";
        var request = new RequestWrapperDTO.Builder<Set<String>, Object>()
            .id(UUID.randomUUID().toString())
            .method("SUBSCRIBE")
            .params(Set.of(stream))
            .build();
        StreamBlockingQueue<String> queue = connection.subscribe(request).get(stream);
        if (queue == null) {
            throw new IllegalStateException("Binance SDK 未创建 1 秒标记价格订阅队列");
        }
        return queue;
    }

    static void ensureConnected(StreamConnectionInterface connection) {
        if (!connection.isConnected()) {
            connection.connect();
        }
        if (!connection.isConnected()) {
            throw new IllegalStateException("Binance SDK WebSocket 订阅前未能建立连接");
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

    private static void stop(StreamConnectionInterface connection) {
        try {
            connection.stop();
        } catch (Exception ex) {
            log.debug("Binance SDK stream close: {}", ex.getClass().getSimpleName());
        }
    }

    boolean connected() {
        if (closed.get()) {
            return false;
        }
        // User-data streams may legitimately have no events for long periods.
        try {
            return connected.get() || connection.isConnected();
        } catch (RuntimeException ex) {
            return connected.get();
        }
    }

    private void consume() {
        try {
            while (!closed.get()) {
                for (BlockingQueue<String> raw : rawQueues) {
                    if (closed.get()) {
                        return;
                    }
                    // Fail before parsing the next message; no stale decision may be made from an overloaded queue.
                    if (raw.size() >= backlogLimit) {
                        log.error("Binance SDK stream backlog reached threshold {}, force reconnect and replay", backlogLimit);
                        fail();
                        return;
                    }
                }
                int handled = drainAvailable(rawQueues, event -> {
                    connected.set(true);
                    events.accept(event);
                }, 256);
                if (handled == 0) {
                    // Avoid waiting on each empty SDK queue in turn; that serialized wait caused
                    // the hot aggregate-trade queue to fall behind when sibling streams were idle.
                    TimeUnit.MILLISECONDS.sleep(1);
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException ex) {
            log.warn("Binance SDK Streams subscription stopped: {}", ex.getClass().getSimpleName());
            fail();
        }
    }

    static int drainAvailable(List<? extends BlockingQueue<String>> queues, Consumer<String> events, int maxEvents) {
        int handled = 0;
        while (handled < maxEvents) {
            boolean found = false;
            for (BlockingQueue<String> queue : queues) {
                if (handled >= maxEvents) {
                    break;
                }
                String event = queue.poll();
                if (event != null) {
                    events.accept(event);
                    handled++;
                    found = true;
                }
            }
            if (!found) {
                break;
            }
        }
        return handled;
    }

    private void fail() {
        if (closed.compareAndSet(false, true)) {
            connected.set(false);
            stop(connection);
            try {
                disconnected.run();
            } catch (RuntimeException ex) {
                log.warn("Binance Streams disconnected callback failed");
            }
        }
    }

    /**
     * 关闭 SDK 流并释放传输资源。
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            connected.set(false);
            consumer.interrupt();
            stop(connection);
        }
    }
}
