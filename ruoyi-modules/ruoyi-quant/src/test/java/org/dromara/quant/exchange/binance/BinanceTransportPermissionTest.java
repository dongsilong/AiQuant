package org.dromara.quant.exchange.binance;

import cn.dev33.satoken.annotation.SaCheckPermission;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class BinanceTransportPermissionTest {
    @Test
    void hotSwitchIsDeniedWithoutOperatorPermissionOrRiskAcknowledgement() throws Exception {
        var method = BinanceWebSocketTransportController.class.getMethod("change", String.class, boolean.class);
        var permission = method.getAnnotation(SaCheckPermission.class);
        assertNotNull(permission);
        assertTrue(Arrays.asList(permission.value()).contains("quant:transport:switch"));
        var adapter = mock(BinanceGatewayAdapter.class);
        var controller = new BinanceWebSocketTransportController(adapter);
        assertThrows(IllegalArgumentException.class, () -> controller.change("BINANCE_SDK", false));
        verify(adapter, never()).switchWebsocketTransport(anyString());
        controller.change("RAW_OKHTTP", true);
        verify(adapter).switchWebsocketTransport("RAW_OKHTTP");
    }
}
