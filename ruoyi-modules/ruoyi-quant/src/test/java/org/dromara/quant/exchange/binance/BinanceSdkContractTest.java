package org.dromara.quant.exchange.binance;

import com.binance.connector.client.derivatives_trading_usds_futures.rest.api.DerivativesTradingUsdsFuturesRestApi;
import com.binance.connector.client.derivatives_trading_usds_futures.websocket.stream.api.DerivativesTradingUsdsFuturesWebSocketStreams;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 无需账户、无需网络的官方 Java Connector 契约检查。
 * 项目固定使用 io.github.binance:binance-derivatives-trading-usds-futures:12.0.3。
 * 新增直连 REST/WS 前必须核实 SDK 是否已有该接口。
 */
@Tag("dev")
class BinanceSdkContractTest {
    @Test
    void officialRestSdkProvidesAllRequiredTradingAndAccountOperations() {
        Set<String> methods = Arrays.stream(DerivativesTradingUsdsFuturesRestApi.class.getMethods())
            .map(Method::getName).collect(Collectors.toSet());
        for (String required : new String[] {
            "newOrder", "newAlgoOrder", "queryOrder", "accountTradeList",
            "positionInformationV3", "currentAllOpenOrders",
            "startUserDataStream", "keepaliveUserDataStream", "closeUserDataStream"
        }) {
            assertTrue(methods.contains(required), "SDK 12.0.3 lacks required REST method: " + required);
        }
    }

    @Test
    void officialWebSocketSdkExposesKlineAndUserDataStreams() {
        Set<String> methods = Arrays.stream(DerivativesTradingUsdsFuturesWebSocketStreams.class.getMethods())
            .map(Method::getName).collect(Collectors.toSet());
        assertTrue(methods.contains("klineCandlestickStreams"));
        assertTrue(methods.contains("userData"));
        // SDK API 存在不等于证明消息队列、重连、原始事件兼容性满足实盘要求。
    }
}
