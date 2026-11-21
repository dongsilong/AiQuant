package org.dromara.quant.exchange.binance;

import com.binance.connector.client.common.websocket.adapter.stream.StreamConnectionWrapper;
import com.binance.connector.client.common.websocket.configuration.WebSocketClientConfiguration;
import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jetty.websocket.api.Callback;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Public SDK listener type so Jetty can inspect inherited WebSocket callbacks.
 * MarketStreamHub remains the single owner of market reconnect and replay.
 */
@Slf4j
public class MarketStreamConnectionWrapper extends StreamConnectionWrapper {
    private final Runnable disconnected;
    private final String symbol;
    private final String interval;
    private final String connectionId = UUID.randomUUID().toString();
    private final AtomicBoolean intentionalClose = new AtomicBoolean();
    private final AtomicBoolean disconnectNotified = new AtomicBoolean();

    void markIntentionalClose() {
        intentionalClose.set(true);
    }

    public MarketStreamConnectionWrapper(WebSocketClientConfiguration configuration, Gson gson, Runnable disconnected, String symbol, String interval) {
        super(configuration, gson);
        this.disconnected = disconnected;
        this.symbol = symbol;
        this.interval = interval;
    }

    /**
     * Jetty 12 requires the callback-based close signature. Do not also declare the
     * deprecated two-argument overload: Jetty rejects endpoints exposing both forms.
     */
    public void onWebSocketClose(int statusCode, String reason, Callback callback) {
        try {
            if (intentionalClose.get()) {
                log.info("Binance SDK 行情 WebSocket 主动关闭 connectionId={} symbol={} interval={} closeCode={} reason={}",
                    connectionId, symbol, interval, statusCode, reason);
            } else {
                log.warn("Binance SDK 行情 WebSocket 意外关闭 connectionId={} symbol={} interval={} closeCode={} reason={}",
                    connectionId, symbol, interval, statusCode, reason);
                notifyDisconnected();
            }
        } finally {
            // Complete Jetty's close callback even if the hub's disconnect handler fails.
            callback.succeed();
        }
    }

    @Override
    public void onWebSocketError(Throwable cause) {
        if (intentionalClose.get()) {
            log.debug("Binance SDK 行情 WebSocket 主动关闭期间收到传输回调 connectionId={} symbol={} interval={} errorType={}",
                connectionId, symbol, interval, cause == null ? "unknown" : cause.getClass().getSimpleName());
            return;
        }
        log.warn("Binance SDK 行情 WebSocket 传输异常 connectionId={} symbol={} interval={} errorType={}", connectionId, symbol, interval,
            cause == null ? "unknown" : cause.getClass().getSimpleName());
        notifyDisconnected();
    }

    private void notifyDisconnected() {
        if (disconnectNotified.compareAndSet(false, true)) {
            disconnected.run();
        }
    }
}
