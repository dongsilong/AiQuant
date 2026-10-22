package org.dromara.quant.market.controller;

import org.dromara.quant.exchange.ExchangeGateway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class QuantRealtimeMarketControllerTest {
    @Test
    void routesKlinesToTheSelectedMarketEnvironment() {
        ExchangeGateway gateway = mock(ExchangeGateway.class);
        var controller = new QuantRealtimeMarketController(gateway);
        when(gateway.queryKlines("TESTNET", "BRUSDT", "15m", 1000)).thenReturn(List.of());
        assertTrue(controller.klines("BRUSDT", "15m", 5000, "TESTNET").getData().isEmpty());
        verify(gateway).queryKlines("TESTNET", "BRUSDT", "15m", 1000);
        verify(gateway, never()).queryKlines("BRUSDT", "15m", 1000);
    }

    @Test
    void preservesLiveRoutingAndTheLowerLimit() {
        ExchangeGateway gateway = mock(ExchangeGateway.class);
        var controller = new QuantRealtimeMarketController(gateway);
        when(gateway.queryKlines("LIVE", "BTCUSDT", "1m", 1)).thenReturn(List.of());
        assertTrue(controller.klines("BTCUSDT", "1m", 0, "LIVE").getData().isEmpty());
        verify(gateway).queryKlines("LIVE", "BTCUSDT", "1m", 1);
    }
}
