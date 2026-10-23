package org.dromara.quant.exchange.binance;

import com.binance.connector.client.common.websocket.adapter.stream.StreamConnectionWrapper;
import com.binance.connector.client.common.websocket.configuration.WebSocketClientConfiguration;
import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jetty.websocket.api.Callback;

/**
 * Public SDK listener type so Jetty can inspect inherited WebSocket callbacks.
 * MarketStreamHub remains the single owner of market reconnect and replay.
 */
@Slf4j
public class MarketStreamConnectionWrapper extends StreamConnectionWrapper {
    private final Runnable disconnected;
    private final String symbol;
    private final String interval;

    public MarketStreamConnectionWrapper(WebSocketClientConfiguration configuration, Gson gson, Runnable disconnected, String symbol, String interval) {
        super(configuration, gson);
        this.disconnected = disconnected;
        this.symbol = symbol;
        this.interval = interval;
    }

    @Override
    public void onWebSocketClose(int statusCode, String reason, Callback callback) {
        // Do not let the SDK reconnect this generation behind the hub's recovery gate.
        BinanceSdkStreamTransport.notifyMarketSocketClosed(statusCode, reason, callback, disconnected, symbol, interval);
    }

    @Override
    public void onWebSocketError(Throwable cause) {
        log.warn("Binance SDK 行情 WebSocket 传输异常 symbol={} interval={} errorType={}", symbol, interval,
            cause == null ? "unknown" : cause.getClass().getSimpleName());
        disconnected.run();
    }
}
