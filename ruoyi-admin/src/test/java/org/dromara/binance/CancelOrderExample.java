package org.dromara.binance;

import com.binance.connector.client.common.ApiException;
import com.binance.connector.client.common.ApiResponse;
import com.binance.connector.client.common.configuration.ClientConfiguration;
import com.binance.connector.client.common.configuration.SignatureConfiguration;
import com.binance.connector.client.derivatives_trading_usds_futures.rest.DerivativesTradingUsdsFuturesRestApiUtil;
import com.binance.connector.client.derivatives_trading_usds_futures.rest.api.DerivativesTradingUsdsFuturesRestApi;
import com.binance.connector.client.derivatives_trading_usds_futures.rest.model.CancelAllOpenOrdersResponse;
import com.binance.connector.client.derivatives_trading_usds_futures.rest.model.CancelOrderResponse;
import com.binance.connector.client.derivatives_trading_usds_futures.rest.model.CheckServerTimeResponse;
import com.binance.connector.client.derivatives_trading_usds_futures.rest.model.OrderBookResponse;
import org.dromara.common.core.utils.DateUtils;

import java.io.IOException;

/** API examples for TradeApi */
public class CancelOrderExample {
    private DerivativesTradingUsdsFuturesRestApi api;

    public DerivativesTradingUsdsFuturesRestApi getApi() {
        if (api == null) {
            ClientConfiguration clientConfiguration =
                    DerivativesTradingUsdsFuturesRestApiUtil.getClientConfiguration();
            SignatureConfiguration signatureConfiguration = new SignatureConfiguration();
            signatureConfiguration.setApiKey("MYo3ipbZULoMbSDQ9hsSkZHaXiF67Id4WlXLTGeh5Azgjj3doJaVgggpTVcWwd0D");
            signatureConfiguration.setSecretKey("mmNAGZXkF9p0EUbyNrZuQ6VOanjL6XxMTdx28vo4aW13bnrnMyU3Lncluevs2vrO");
            clientConfiguration.setSignatureConfiguration(signatureConfiguration);
            api = new DerivativesTradingUsdsFuturesRestApi(clientConfiguration);
        }
        return api;
    }

    /**
     * Cancel Order (TRADE)
     *
     * <p>Cancel an active order. Weight(IP): 1 Security Type: TRADE Notes: - Either
     * &#x60;orderId&#x60; or &#x60;origClientOrderId&#x60; must be sent.
     *
     * @throws ApiException if the Api call fails
     */
    public void cancelOrderExample() throws ApiException, IOException {
        String symbol = "BRUSDT";
        Long orderId = 283194212L;
        String origClientOrderId = "myOrder1";
        Long recvWindow = 5000L;
        ApiResponse<CancelOrderResponse> response =
                getApi().cancelOrder(symbol, orderId, origClientOrderId, recvWindow);
        System.out.println(response.getData());

    }

    public static void main(String[] args) throws Exception {
        // 启动时先同步 Binance 服务器时间
        BinanceTimeSync.sync();

        CancelOrderExample example = new CancelOrderExample();
        example.getApi();
        example.cancelOrderExample();
    }
}
