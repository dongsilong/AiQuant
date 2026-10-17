package org.dromara.quant.exchange.binance;

import com.sun.net.httpserver.HttpServer;
import org.dromara.quant.config.QuantProperties;
import org.dromara.quant.exchange.ExchangeGateway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Tag("dev")
class BinanceGatewayConnectivityTest {

    @Test
    void liveCredentialFailsClosedWhenWithdrawalIsEnabledOrUnknown() {
        var enabled = BinanceGatewayAdapter.tradingPermissions(true, true);
        assertFalse(enabled.success());
        assertTrue(enabled.canTrade());
        assertFalse(enabled.withdrawalDisabled());

        var missing = BinanceGatewayAdapter.tradingPermissions(true, null);
        assertFalse(missing.success());
        assertFalse(missing.withdrawalDisabled());

        var safe = BinanceGatewayAdapter.tradingPermissions(true, false);
        assertTrue(safe.success());
        assertTrue(safe.withdrawalDisabled());

        var denied = BinanceGatewayAdapter.tradingPermissions(false, false);
        assertFalse(denied.success());
        assertFalse(denied.canTrade());
        assertFalse(BinanceGatewayAdapter.tradingPermissions(null, false).success());
    }

    @Test
    void testsBinanceNetworkWithoutCredentialHeaders() throws IOException {
        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> apiKeyHeader = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestPath.set(exchange.getRequestURI().getPath());
            apiKeyHeader.set(exchange.getRequestHeaders().getFirst("X-MBX-APIKEY"));
            byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            QuantProperties properties = new QuantProperties();
            properties.getBinance().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());

            ExchangeGateway.ConnectivityResult result = new BinanceGatewayAdapter(properties).testConnectivity();

            assertTrue(result.success());
            assertEquals("/fapi/v1/ping", requestPath.get());
            assertFalse(apiKeyHeader.get() != null);
        } finally {
            server.stop(0);
        }
    }
}
