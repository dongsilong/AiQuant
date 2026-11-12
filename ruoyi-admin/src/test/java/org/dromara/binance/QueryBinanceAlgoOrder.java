package org.dromara.binance;

import com.binance.connector.client.common.ApiClient;
import com.binance.connector.client.common.ApiResponse;
import com.binance.connector.client.common.configuration.ClientConfiguration;
import com.binance.connector.client.common.configuration.SignatureConfiguration;
import com.binance.connector.client.derivatives_trading_usds_futures.rest.api.DerivativesTradingUsdsFuturesRestApi;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class QueryBinanceAlgoOrder {

    private static final String BASE_URL = "https://demo-fapi.binance.com";

    private static final String SYMBOL = "JCTUSDT";

    private static final String CLIENT_ALGO_ID =
        "QHARD_d1f8b3f039a0495e9db63a796602";

    private static final Long EXPECTED_ACTUAL_ORDER_ID = 271997586L;

    private static final Long RECV_WINDOW = 60000L;

    public static void main(String[] args) {

        // 从环境变量读取 API 凭证，不要将密钥写入源码。
        String apiKey = "9hKRLOiXLEHCiRHKN8qswRAvpYUPpFjM5i1WdOuOd9e3SUAZRLZOCugDgbKbg2cb";
        String secretKey = "JtfL6iQ9RjH6WSsvsNVSdhR25OkitaD96ln3p0v6pUjBWELUes0y3OyaBjny2C1U";

        if (apiKey == null || apiKey.isBlank()
            || secretKey == null || secretKey.isBlank()) {
            throw new IllegalStateException("缺少 Binance API 环境变量");
        }

        // 初始化 SDK。
        ClientConfiguration config = new ClientConfiguration();
        config.setUrl(BASE_URL);
        config.setRetries(0);

        SignatureConfiguration signature = new SignatureConfiguration();
        signature.setApiKey(apiKey);
        signature.setSecretKey(secretKey);
        config.setSignatureConfiguration(signature);

        ApiClient apiClient = new ApiClient(config);

        DerivativesTradingUsdsFuturesRestApi client =
            new DerivativesTradingUsdsFuturesRestApi(apiClient);

        Gson gson = new GsonBuilder().setPrettyPrinting().create();

        try {
            // 第一步：根据 clientAlgoId 精确查询 Algo 保护单。
            System.out.println("========== 1. 查询 Algo 保护单 ==========");

            ApiResponse<?> algoResponse = client.queryAlgoOrder(
                null,
                CLIENT_ALGO_ID,
                RECV_WINDOW
            );

            Object algo = algoResponse == null
                ? null
                : algoResponse.getData();

            if (algo == null) {
                System.out.println("Binance 未返回 Algo 保护单数据");
                return;
            }

            // 输出 Binance 原始返回对象，保留所有 SDK 可见字段。
            System.out.println(gson.toJson(algo));

            // 读取 actualOrderId。
            Object actualOrderIdValue = algo.getClass()
                .getMethod("getActualOrderId")
                .invoke(algo);

            Long actualOrderId = actualOrderIdValue == null
                ? null
                : Long.valueOf(actualOrderIdValue.toString());

            System.out.println("========== Algo 关键字段 ==========");
            System.out.println("symbol = " +
                algo.getClass().getMethod("getSymbol").invoke(algo));
            System.out.println("algoId = " +
                algo.getClass().getMethod("getAlgoId").invoke(algo));
            System.out.println("clientAlgoId = " +
                algo.getClass().getMethod("getClientAlgoId").invoke(algo));
            System.out.println("algoStatus = " +
                algo.getClass().getMethod("getAlgoStatus").invoke(algo));
            System.out.println("actualOrderId = " + actualOrderId);
            System.out.println("actualQty = " +
                algo.getClass().getMethod("getActualQty").invoke(algo));
            System.out.println("actualPrice = " +
                algo.getClass().getMethod("getActualPrice").invoke(algo));
            System.out.println("quantity = " +
                algo.getClass().getMethod("getQuantity").invoke(algo));
            System.out.println("triggerPrice = " +
                algo.getClass().getMethod("getTriggerPrice").invoke(algo));

            // 第二步：通过 Algo 返回的 actualOrderId 查询实际子订单。
            if (actualOrderId == null || actualOrderId <= 0) {
                System.out.println("Algo 未返回有效 actualOrderId，无法继续查询子订单");
                return;
            }

            System.out.println("========== 2. 查询实际子订单 ==========");

            ApiResponse<?> orderResponse = client.queryOrder(
                SYMBOL,
                actualOrderId,
                null,
                RECV_WINDOW
            );

            Object order = orderResponse == null
                ? null
                : orderResponse.getData();

            if (order == null) {
                System.out.println("Binance 未返回实际子订单数据");
                return;
            }

            System.out.println(gson.toJson(order));

            System.out.println("========== 子订单关键字段 ==========");
            printField(order, "getSymbol", "symbol");
            printField(order, "getOrderId", "orderId");
            printField(order, "getClientOrderId", "clientOrderId");
            printField(order, "getSide", "side");
            printField(order, "getPositionSide", "positionSide");
            printField(order, "getType", "type");
            printField(order, "getStatus", "status");
            printField(order, "getOrigQty", "origQty");
            printField(order, "getExecutedQty", "executedQty");
            printField(order, "getAvgPrice", "avgPrice");
            printField(order, "getReduceOnly", "reduceOnly");
            printField(order, "getClosePosition", "closePosition");

            System.out.println("========== 3. 身份核对 ==========");

            System.out.println("预期 actualOrderId = " +
                EXPECTED_ACTUAL_ORDER_ID);
            System.out.println("实际 actualOrderId = " + actualOrderId);
            System.out.println("actualOrderId 匹配 = " +
                EXPECTED_ACTUAL_ORDER_ID.equals(actualOrderId));

        } catch (Exception e) {
            System.err.println("查询 Binance Algo 保护单失败："
                + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void printField(
        Object bean,
        String getter,
        String label
    ) throws Exception {
        Object value = bean.getClass()
            .getMethod(getter)
            .invoke(bean);
        System.out.println(label + " = " + value);
    }
}
