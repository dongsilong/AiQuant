package org.dromara.binance;

import com.binance.connector.client.common.ApiException;
import com.binance.connector.client.common.ApiResponse;
import com.binance.connector.client.common.configuration.ClientConfiguration;
import com.binance.connector.client.common.configuration.SignatureConfiguration;
import com.binance.connector.client.derivatives_trading_usds_futures.rest.DerivativesTradingUsdsFuturesRestApiUtil;
import com.binance.connector.client.derivatives_trading_usds_futures.rest.api.DerivativesTradingUsdsFuturesRestApi;
import com.binance.connector.client.derivatives_trading_usds_futures.rest.model.AccountInformationV3Response;
import com.binance.connector.client.derivatives_trading_usds_futures.rest.model.CheckServerTimeResponse;
import org.dromara.common.core.utils.DateUtils;

import java.io.IOException;

/** API examples for AccountApi */
public class AccountInformationV3Example {
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
     * Account Information V3 (USER_DATA)
     *
     * <p>Get current account information. User in single-asset/ multi-assets mode will see
     * different value, see comments in response section for detail. Weight(IP): 5 Security Type:
     * USER_DATA
     *
     * @throws ApiException if the Api call fails
     */
    public void accountInformationV3Example() throws ApiException, IOException {
        Long recvWindow = 10000L;
        ApiResponse<AccountInformationV3Response> response = getApi().accountInformationV3(recvWindow);
        System.out.println(response.getData());
    }

    public static void main(String[] args) throws Exception {
        // 启动时先同步 Binance 服务器时间
        BinanceTimeSync.sync();

        AccountInformationV3Example example = new AccountInformationV3Example();
        example.getApi();

        ApiResponse<CheckServerTimeResponse> timeResponse = example.getApi().checkServerTime();
        Long serverTime = timeResponse.getData().getServerTime();
        System.out.println(DateUtils.date(serverTime));

        System.out.println(DateUtils.now());

        example.accountInformationV3Example();
    }
}
