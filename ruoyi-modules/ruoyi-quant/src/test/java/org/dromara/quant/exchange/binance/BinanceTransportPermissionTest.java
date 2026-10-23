package org.dromara.quant.exchange.binance;

import cn.dev33.satoken.annotation.SaCheckPermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class BinanceTransportPermissionTest {
    @Test
    void currentTransportEndpointIsAvailableToViewPermission() throws Exception {
        var method = BinanceWebSocketTransportController.class.getMethod("current");
        var mapping = method.getAnnotation(GetMapping.class);
        assertNotNull(mapping);
        assertArrayEquals(new String[]{"/transport"}, mapping.value());
        var permission = method.getAnnotation(SaCheckPermission.class);
        assertNotNull(permission);
        assertTrue(Arrays.asList(permission.value()).contains("quant:transport:view"));
    }

    @Test
    void hotSwitchIsDeniedWithoutOperatorPermissionOrRiskAcknowledgement() throws Exception {
        var method = BinanceWebSocketTransportController.class.getMethod("change", String.class, boolean.class);
        var permission = method.getAnnotation(SaCheckPermission.class);
        assertNotNull(permission);
        assertTrue(Arrays.asList(permission.value()).contains("quant:transport:switch"));
        var adapter = mock(BinanceGatewayAdapter.class);
        when(adapter.websocketTransport()).thenReturn("RAW_OKHTTP");
        var controller = new BinanceWebSocketTransportController(adapter);
        assertThrows(IllegalArgumentException.class, () -> controller.change("BINANCE_SDK", false));
        verify(adapter, never()).switchWebsocketTransport(anyString());
        controller.change("RAW_OKHTTP", true);
        verify(adapter).switchWebsocketTransport("RAW_OKHTTP");
    }
}
