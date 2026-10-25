package org.dromara.quant.exchange.binance;

import com.binance.connector.client.common.websocket.adapter.stream.StreamConnectionWrapper;
import com.binance.connector.client.common.websocket.configuration.WebSocketClientConfiguration;
import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
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
     * StreamConnectionWrapper in the pinned Binance SDK exposes Jetty's two-argument
     * WebSocketListener close callback. Keep the signature aligned with that API so
     * disconnect recovery is invoked by the actual SDK endpoint callback.
     */
    @Override
    public void onWebSocketClose(int statusCode, String reason) {
        BinanceSdkStreamTransport.notifyMarketSocketClosed(connectionId, statusCode, reason, disconnected, symbol, interval);
    }

    @Override
    public void onWebSocketError(Throwable cause) {
        log.warn("Binance SDK 行情 WebSocket 传输异常 connectionId={} symbol={} interval={} errorType={}", connectionId, symbol, interval,
            cause == null ? "unknown" : cause.getClass().getSimpleName());
        disconnected.run();
    }
}
