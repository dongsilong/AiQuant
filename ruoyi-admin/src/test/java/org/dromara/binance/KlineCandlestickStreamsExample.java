package org.dromara.binance;

import com.binance.connector.client.common.ApiException;
import com.binance.connector.client.common.websocket.configuration.WebSocketClientConfiguration;
import com.binance.connector.client.common.websocket.service.StreamBlockingQueueWrapper;
import com.binance.connector.client.derivatives_trading_usds_futures.websocket.stream.DerivativesTradingUsdsFuturesWebSocketStreamsUtil;
import com.binance.connector.client.derivatives_trading_usds_futures.websocket.stream.api.DerivativesTradingUsdsFuturesWebSocketStreams;
import com.binance.connector.client.derivatives_trading_usds_futures.websocket.stream.model.Interval;
import com.binance.connector.client.derivatives_trading_usds_futures.websocket.stream.model.KlineCandlestickStreamsRequest;
import com.binance.connector.client.derivatives_trading_usds_futures.websocket.stream.model.KlineCandlestickStreamsResponse;
import org.checkerframework.checker.units.qual.K;

/**
 * API examples for MarketApi
 */
public class KlineCandlestickStreamsExample {
    private DerivativesTradingUsdsFuturesWebSocketStreams api;

    public static void main(String[] args) {
        KlineCandlestickStreamsExample example = new KlineCandlestickStreamsExample();
        example.getApi();
        example.klineCandlestickStreamsExample();
    }

    public DerivativesTradingUsdsFuturesWebSocketStreams getApi() {
        if (api == null) {
            WebSocketClientConfiguration clientConfiguration = DerivativesTradingUsdsFuturesWebSocketStreamsUtil.getClientConfiguration();
            api = new DerivativesTradingUsdsFuturesWebSocketStreams(clientConfiguration);
        }
        return api;
    }

    /**
     * Kline/Candlestick Streams
     *
     * <p>The Kline/Candlestick Stream push updates to the current klines/candlestick every 250
     * milliseconds (if existing). &gt; **After CM migration**, both &#x60;fstream&#x60; and
     * &#x60;dstream&#x60; may subscribe to either UM or CM symbols on this stream. Update Speed:
     * 250ms
     *
     * @throws ApiException if the Api call fails
     */
    public void klineCandlestickStreamsExample() {
        KlineCandlestickStreamsRequest request = new KlineCandlestickStreamsRequest().symbol("btcusdt").interval(Interval.INTERVAL_1m);
        StreamBlockingQueueWrapper<KlineCandlestickStreamsResponse> response = getApi().klineCandlestickStreams(request);
        try {
            while (true) {
                System.out.println(response.take());
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
