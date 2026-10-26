package org.dromara.quant.exchange.binance;

import com.binance.connector.client.common.websocket.adapter.stream.StreamConnectionWrapper;
import com.binance.connector.client.common.websocket.configuration.WebSocketClientConfiguration;
import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jetty.websocket.api.Callback;

import java.util.UUID;

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
            BinanceSdkStreamTransport.notifyMarketSocketClosed(connectionId, statusCode, reason, disconnected, symbol, interval);
        } finally {
            // Complete Jetty's close callback even if the hub's disconnect handler fails.
            callback.succeed();
        }
    }

    @Override
    public void onWebSocketError(Throwable cause) {
        log.warn("Binance SDK 行情 WebSocket 传输异常 connectionId={} symbol={} interval={} errorType={}", connectionId, symbol, interval,
            cause == null ? "unknown" : cause.getClass().getSimpleName());
        disconnected.run();
    }
}
