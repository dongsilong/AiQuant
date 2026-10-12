package org.dromara.quant;

import com.binance.connector.client.common.ApiException;
import com.binance.connector.client.common.ApiResponse;
import com.binance.connector.client.common.configuration.ClientConfiguration;
import com.binance.connector.client.common.configuration.SignatureConfiguration;
import com.binance.connector.client.derivatives_trading_usds_futures.rest.DerivativesTradingUsdsFuturesRestApiUtil;
import com.binance.connector.client.derivatives_trading_usds_futures.rest.api.DerivativesTradingUsdsFuturesRestApi;
import com.binance.connector.client.derivatives_trading_usds_futures.rest.model.ExchangeInformationResponse;
import com.binance.connector.client.derivatives_trading_usds_futures.rest.model.ExchangeInformationResponseSymbolsInner;
import com.binance.connector.client.derivatives_trading_usds_futures.rest.model.ExchangeInformationResponseSymbolsInnerFiltersInner;

import java.io.IOException;
import java.util.List;

public class ExchangeInformationExample {
    private DerivativesTradingUsdsFuturesRestApi api;

    public DerivativesTradingUsdsFuturesRestApi getApi() {
        if (api == null) {
            ClientConfiguration clientConfiguration =
                    DerivativesTradingUsdsFuturesRestApiUtil.getClientConfiguration();
            SignatureConfiguration signatureConfiguration = new SignatureConfiguration();
            // 公共市场数据无需 API Key，下面两行保持注释即可
            // signatureConfiguration.setApiKey("apiKey");
            // signatureConfiguration.setPrivateKey("path/to/private.key");
            clientConfiguration.setSignatureConfiguration(signatureConfiguration);
            api = new DerivativesTradingUsdsFuturesRestApi(clientConfiguration);
        }
        return api;
    }

    /** 去除小数末尾无意义的零，例如 "0.01000000" -> "0.01"，"1.00000000" -> "1" */
    private static String trimTrailingZeros(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        if (value.contains(".")) {
            value = value.replaceAll("0+$", ""); // 去掉末尾连续的零
            if (value.endsWith(".")) {
                value = value.substring(0, value.length() - 1); // 去掉小数点
            }
        }
        return value;
    }

    public void exchangeInformationExample() throws ApiException, IOException {
        ApiResponse<ExchangeInformationResponse> response = getApi().exchangeInformation();
        ExchangeInformationResponse data = response.getData();
        List<ExchangeInformationResponseSymbolsInner> symbols = data.getSymbols();

        System.out.println("共获取到 " + symbols.size() + " 个 U 本位合约交易对：");
        System.out.println(
                "========================================================================================================================");

        for (ExchangeInformationResponseSymbolsInner s : symbols) {
            if (!"TRADING".equals(s.getStatus())) continue;

            // 默认值
            String tickSize = "N/A";
            String stepSize = "N/A";
            String marketStepSize = "N/A";
            String minNotional = "N/A";

            // 遍历 filters，按 filterType 提取对应字段
            if (s.getFilters() != null) {
                for (ExchangeInformationResponseSymbolsInnerFiltersInner f : s.getFilters()) {
                    String type = f.getFilterType();
                    if ("PRICE_FILTER".equals(type)) {
                        tickSize = trimTrailingZeros(f.getTickSize());
                    } else if ("LOT_SIZE".equals(type)) {
                        stepSize = trimTrailingZeros(f.getStepSize());
                    } else if ("MARKET_LOT_SIZE".equals(type)) {
                        marketStepSize = trimTrailingZeros(f.getStepSize());
                    } else if ("MIN_NOTIONAL".equals(type) || "NOTIONAL".equals(type)) {
                        // 12.x 版本使用 getNotional()
                        minNotional = trimTrailingZeros(f.getNotional());
                    }
                }
            }

            System.out.printf(
                    "%-18s | tick: %-14s | step: %-14s | mktStep: %-14s | minNotional: %-12s | 类型:"
                            + " %-12s | 基础: %-6s | 计价: %-6s | 状态: %s%n",
                    s.getSymbol(),
                    tickSize,
                    stepSize,
                    marketStepSize,
                    minNotional,
                    s.getContractType() != null ? s.getContractType() : "N/A",
                    s.getBaseAsset() != null ? s.getBaseAsset() : "N/A",
                    s.getQuoteAsset() != null ? s.getQuoteAsset() : "N/A",
                    s.getStatus());
        }

        System.out.println(
                "========================================================================================================================");
        System.out.println("注：最大杠杆需单独调用 GET /fapi/v1/leverageBracket 获取。");
    }

    public static void main(String[] args) throws IOException, ApiException {
        new ExchangeInformationExample().exchangeInformationExample();
    }
}
