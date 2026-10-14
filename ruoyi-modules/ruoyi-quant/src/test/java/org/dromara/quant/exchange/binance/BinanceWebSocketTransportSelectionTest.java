package org.dromara.quant.exchange.binance;

import org.dromara.quant.config.QuantProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@Tag("dev")
class BinanceWebSocketTransportSelectionTest {
    @Test
    void rawTransportIsDefaultAndSdkModeIsExplicit() {
        var config = new QuantProperties();
        var gateway = new BinanceGatewayAdapter(config);
        assertFalse(gateway.sdkStreamsEnabled());
        assertEquals(0, gateway.switchWebsocketTransport("BINANCE_SDK"));
        assertTrue(gateway.sdkStreamsEnabled());
        assertEquals("BINANCE_SDK", gateway.websocketTransport());
        assertEquals(0, gateway.switchWebsocketTransport("RAW_OKHTTP"));
        assertFalse(gateway.sdkStreamsEnabled());
        assertThrows(IllegalArgumentException.class, () -> gateway.switchWebsocketTransport("unsupported"));
        assertFalse(gateway.sdkStreamsEnabled());
    }
}
