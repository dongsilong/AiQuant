package org.dromara.quant.exchange.binance;

import org.dromara.quant.config.QuantProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Tag("dev")
class BinanceWebSocketTransportSelectionTest {
    @Test
    void incompatibleJettyRuntimeFallsBackBeforeOpeningLiveStreams() {
        var gateway = new BinanceGatewayAdapter(new QuantProperties());
        if (!BinanceGatewayAdapter.sdkJettyBinaryCompatible()) {
            assertEquals("RAW_OKHTTP", gateway.websocketTransport());
            assertFalse(gateway.sdkStreamsEnabled());
        } else {
            assertEquals("BINANCE_SDK", gateway.websocketTransport());
            assertTrue(gateway.sdkStreamsEnabled());
        }
    }

    @Test
    void incompatibleSdkSwitchPreservesExistingSockets() {
        var config = new QuantProperties();
        config.getBinance().setWebsocketTransport("RAW_OKHTTP");
        config.getProxy().setHost("127.0.0.1");
        config.getProxy().setPort(7890);
        var gateway = new BinanceGatewayAdapter(config);
        var closed = new AtomicInteger();
        var notified = new AtomicInteger();
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(gateway, "trackWebsocket",
            (Runnable) closed::incrementAndGet, (Runnable) notified::incrementAndGet);
        if (!BinanceGatewayAdapter.sdkJettyBinaryCompatible()) {
            assertThrows(IllegalStateException.class, () -> gateway.switchWebsocketTransport("BINANCE_SDK"));
            assertEquals("RAW_OKHTTP", gateway.websocketTransport());
            assertEquals(0, closed.get());
            assertEquals(0, notified.get());
        } else {
            assertEquals(1, gateway.switchWebsocketTransport("BINANCE_SDK"));
            assertEquals("BINANCE_SDK", gateway.websocketTransport());
            assertEquals(1, closed.get());
            assertEquals(1, notified.get());
        }
        assertThrows(IllegalArgumentException.class, () -> gateway.switchWebsocketTransport("unsupported"));
    }

    @Test
    void explicitRawTransportAlwaysRemainsAvailable() {
        var config = new QuantProperties();
        config.getBinance().setWebsocketTransport("RAW_OKHTTP");
        var gateway = new BinanceGatewayAdapter(config);
        assertEquals("RAW_OKHTTP", gateway.websocketTransport());
        assertFalse(gateway.sdkStreamsEnabled());
        assertEquals(0, gateway.switchWebsocketTransport("RAW_OKHTTP"));
    }
}
