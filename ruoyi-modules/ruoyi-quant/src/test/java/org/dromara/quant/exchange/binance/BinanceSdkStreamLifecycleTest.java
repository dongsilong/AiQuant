package org.dromara.quant.exchange.binance;

import com.binance.connector.client.common.websocket.adapter.stream.StreamConnectionInterface;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BinanceSdkStreamLifecycleTest {

    @Test
    void stopsConnectionWhenMarketApiHandshakeFails() throws Exception {
        var connection = mock(StreamConnectionInterface.class);
        var failure = new IllegalStateException("TLS handshake failed");
        doThrow(failure).when(connection).connect();

        var actual = assertThrows(IllegalStateException.class, () ->
            BinanceSdkStreamTransport.market(connection, "btcusdt", "1m", event -> {}, () -> {}, 2048));

        assertSame(failure, actual);
        verify(connection).stop();
    }

    @Test
    void stopsConnectionWhenSubscriptionFails() throws Exception {
        var connection = mock(StreamConnectionInterface.class);
        when(connection.isConnected()).thenReturn(true);
        var failure = new IllegalStateException("subscription failed");
        when(connection.subscribe(any())).thenThrow(failure);

        var actual = assertThrows(IllegalStateException.class, () ->
            BinanceSdkStreamTransport.market(connection, "btcusdt", "1m", event -> {}, () -> {}, 2048));

        assertSame(failure, actual);
        verify(connection).stop();
    }

    @Test
    void preservesHandshakeFailureWhenCleanupAlsoFails() throws Exception {
        var connection = mock(StreamConnectionInterface.class);
        var failure = new IllegalStateException("TLS handshake failed");
        doThrow(failure).when(connection).connect();
        doThrow(new IllegalStateException("stop failed")).when(connection).stop();

        var actual = assertThrows(IllegalStateException.class, () ->
            BinanceSdkStreamTransport.market(connection, "btcusdt", "1m", event -> {}, () -> {}, 2048));

        assertSame(failure, actual);
        verify(connection).stop();
    }
}
