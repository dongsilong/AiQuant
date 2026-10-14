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
        config.getBinance().setWebsocketTransport("BINANCE_SDK");
        assertTrue(gateway.sdkStreamsEnabled());
        config.getBinance().setWebsocketTransport("RAW_OKHTTP");
        assertFalse(gateway.sdkStreamsEnabled());
        config.getBinance().setWebsocketTransport("unsupported");
        assertThrows(IllegalStateException.class, gateway::sdkStreamsEnabled);
    }
}
