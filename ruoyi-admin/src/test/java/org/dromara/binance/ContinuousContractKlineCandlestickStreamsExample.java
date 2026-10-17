package org.dromara.binance;

import com.binance.connector.client.common.ApiException;
import com.binance.connector.client.common.websocket.configuration.WebSocketClientConfiguration;
import com.binance.connector.client.common.websocket.service.StreamBlockingQueueWrapper;
import com.binance.connector.client.derivatives_trading_usds_futures.websocket.stream.DerivativesTradingUsdsFuturesWebSocketStreamsUtil;
import com.binance.connector.client.derivatives_trading_usds_futures.websocket.stream.api.DerivativesTradingUsdsFuturesWebSocketStreams;
import com.binance.connector.client.derivatives_trading_usds_futures.websocket.stream.model.ContinuousContractKlineCandlestickStreamsRequest;
import com.binance.connector.client.derivatives_trading_usds_futures.websocket.stream.model.ContinuousContractKlineCandlestickStreamsResponse;
import com.binance.connector.client.derivatives_trading_usds_futures.websocket.stream.model.ContractType;
import com.binance.connector.client.derivatives_trading_usds_futures.websocket.stream.model.Interval;

/**
 * API examples for MarketApi
 */
public class ContinuousContractKlineCandlestickStreamsExample {
    private DerivativesTradingUsdsFuturesWebSocketStreams api;

    public static void main(String[] args) {
        ContinuousContractKlineCandlestickStreamsExample example = new ContinuousContractKlineCandlestickStreamsExample();
        example.getApi();
        example.continuousContractKlineCandlestickStreamsExample();
    }

    public DerivativesTradingUsdsFuturesWebSocketStreams getApi() {
        if (api == null) {
            WebSocketClientConfiguration clientConfiguration = DerivativesTradingUsdsFuturesWebSocketStreamsUtil.getClientConfiguration();
            api = new DerivativesTradingUsdsFuturesWebSocketStreams(clientConfiguration);
        }
        return api;
    }

    /**
     * Continuous Contract Kline/Candlestick Streams
     *
     * <p>Continuous Contract Kline/Candlestick Streams &gt; **After CM migration**, both
     * &#x60;fstream&#x60; and &#x60;dstream&#x60; may subscribe to either UM or CM symbols on this
     * stream. Update Speed: 250ms
     *
     * @throws ApiException if the Api call fails
     */
    public void continuousContractKlineCandlestickStreamsExample() {
        ContinuousContractKlineCandlestickStreamsRequest request =
            new ContinuousContractKlineCandlestickStreamsRequest().pair("btcusdt").contractType(ContractType.perpetual).interval(Interval.INTERVAL_1m);
        StreamBlockingQueueWrapper<ContinuousContractKlineCandlestickStreamsResponse> response = getApi().continuousContractKlineCandlestickStreams(request);
        try {
            while (true) {
                System.out.println(response.take());
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
