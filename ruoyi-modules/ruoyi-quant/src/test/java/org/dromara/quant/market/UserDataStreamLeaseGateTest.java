package org.dromara.quant.market;

import org.dromara.quant.credential.domain.QuantCredential;
import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.execution.TradeExecutionApplicationService;
import org.dromara.quant.execution.TradingPreparationService;
import org.dromara.quant.reconcile.mapper.LiveUserStreamLeaseMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** LIVE SSE must never create a second private stream on a non-owner node. */
@Tag("dev")
class UserDataStreamLeaseGateTest {
    @Test
    void liveTerminalCannotSubscribeWithoutDatabaseLease() {
        var gateway = mock(ExchangeGateway.class);
        var lease = mock(LiveUserStreamLeaseMapper.class);
        var hub = new UserDataStreamHub(gateway,
            mock(TradeExecutionApplicationService.class),
            mock(AccountPositionCache.class),
            mock(AccountBalanceCache.class),
            mock(TradingPreparationService.class));
        ReflectionTestUtils.setField(hub, "streamLease", lease);
        var credential = new QuantCredential();
        credential.setId(81L);
        credential.setRunMode("LIVE");
        assertThrows(IllegalStateException.class,
            () -> hub.subscribe(credential, ignored -> {}));
        verifyNoInteractions(gateway);
        hub.destroy();
    }

    @Test
    void expiredOwnershipCannotBeRegistered() {
        var gateway = mock(ExchangeGateway.class);
        var lease = mock(LiveUserStreamLeaseMapper.class);
        var hub = new UserDataStreamHub(gateway,
            mock(TradeExecutionApplicationService.class),
            mock(AccountPositionCache.class),
            mock(AccountBalanceCache.class),
            mock(TradingPreparationService.class));
        ReflectionTestUtils.setField(hub, "streamLease", lease);
        assertThrows(IllegalStateException.class,
            () -> hub.registerLeaseOwner(82L, "foreign-node"));
        verify(lease).owns(82L, "foreign-node");
        hub.destroy();
    }
    @Test
    void healthCheckClosesFormerOwnerConnectionAfterLeaseLoss() throws Exception {
        var gateway = mock(ExchangeGateway.class);
        var lease = mock(LiveUserStreamLeaseMapper.class);
        var positions = mock(AccountPositionCache.class);
        var balances = mock(AccountBalanceCache.class);
        var preparation = mock(TradingPreparationService.class);
        var execution = mock(TradeExecutionApplicationService.class);
        var session = mock(ExchangeGateway.UserDataStreamSession.class);
        when(session.isConnected()).thenReturn(true);
        when(gateway.subscribeUserData(any(), any())).thenReturn(session);
        when(lease.owns(eq(93L), eq("node-a"))).thenReturn(1L, 1L, 1L, 0L);
        var hub = new UserDataStreamHub(gateway, execution, positions, balances, preparation);
        ReflectionTestUtils.setField(hub, "streamLease", lease);
        var credential = new QuantCredential();
        credential.setId(93L);
        credential.setRunMode("LIVE");
        hub.registerLeaseOwner(93L, "node-a");
        hub.subscribe(credential, event -> {});
        hub.healthCheck();
        verify(session).close();
        verify(positions).remove(93L);
        verify(balances).remove(93L);
        hub.destroy();
    }

    @Test
    void explicitLeaseRevocationClosesSocketEvenIfBrowserSseStillSubscribes() throws Exception {
        var gateway = mock(ExchangeGateway.class);
        var lease = mock(LiveUserStreamLeaseMapper.class);
        var positions = mock(AccountPositionCache.class);
        var balances = mock(AccountBalanceCache.class);
        var preparation = mock(TradingPreparationService.class);
        var execution = mock(TradeExecutionApplicationService.class);
        var session = mock(ExchangeGateway.UserDataStreamSession.class);
        when(lease.owns(eq(98L), eq("node-a"))).thenReturn(1L);
        when(gateway.subscribeUserData(any(), any())).thenReturn(session);
        var hub = new UserDataStreamHub(gateway, execution, positions, balances, preparation);
        ReflectionTestUtils.setField(hub, "streamLease", lease);
        var credential = new QuantCredential();
        credential.setId(98L);
        credential.setRunMode("LIVE");
        hub.registerLeaseOwner(98L, "node-a");
        var supervisor = hub.subscribe(credential, ignored -> {});
        var browser = hub.subscribe(credential, ignored -> {});
        assertTrue(hub.hasLocalStream(98L));
        hub.unregisterLeaseOwner(98L, "node-a");
        assertFalse(hub.hasLocalStream(98L));
        verify(session).close();
        verify(positions).remove(98L);
        verify(balances).remove(98L);
        assertThrows(IllegalStateException.class, () -> hub.subscribe(credential, ignored -> {}));
        supervisor.close();
        browser.close();
        hub.destroy();
    }

    @Test
    void leaseLostDuringBlockingGatewayConnectClosesNewlyOpenedPrivateSocket() {
        var gateway = mock(ExchangeGateway.class);
        var lease = mock(LiveUserStreamLeaseMapper.class);
        var positions = mock(AccountPositionCache.class);
        var balances = mock(AccountBalanceCache.class);
        var preparation = mock(TradingPreparationService.class);
        var session = mock(ExchangeGateway.UserDataStreamSession.class);
        when(lease.owns(eq(99L), eq("node-a"))).thenReturn(1L, 1L, 0L);
        when(gateway.subscribeUserData(any(), any())).thenReturn(session);
        var hub = new UserDataStreamHub(gateway,
            mock(TradeExecutionApplicationService.class),
            positions, balances, preparation);
        ReflectionTestUtils.setField(hub, "streamLease", lease);
        var credential = new QuantCredential();
        credential.setId(99L);
        credential.setRunMode("LIVE");
        hub.registerLeaseOwner(99L, "node-a");
        hub.subscribe(credential, ignored -> {});
        verify(session).close();
        verify(positions).disconnect(99L);
        verify(balances).disconnect(99L);
        verify(lease, atLeast(3)).owns(99L, "node-a");
        hub.destroy();
    }

    @Test
    void startupFailureAfterSocketCreationClosesPrivateConnection() {
        var gateway = mock(ExchangeGateway.class);
        var lease = mock(LiveUserStreamLeaseMapper.class);
        var positions = mock(AccountPositionCache.class);
        var balances = mock(AccountBalanceCache.class);
        var preparation = mock(TradingPreparationService.class);
        var session = mock(ExchangeGateway.UserDataStreamSession.class);
        when(lease.owns(eq(103L), eq("node-a"))).thenReturn(1L);
        when(gateway.subscribeUserData(any(), any())).thenReturn(session);
        doThrow(new IllegalStateException("initial cache unavailable"))
            .when(positions).onEvent(eq(103L), any());
        var hub = new UserDataStreamHub(gateway,
            mock(TradeExecutionApplicationService.class), positions, balances, preparation);
        ReflectionTestUtils.setField(hub, "streamLease", lease);
        var credential = new QuantCredential();
        credential.setId(103L);
        credential.setRunMode("LIVE");
        hub.registerLeaseOwner(103L, "node-a");
        hub.subscribe(credential, ignored -> {});
        verify(session).close();
        verify(positions).disconnect(103L);
        verify(balances).disconnect(103L);
        hub.destroy();
    }

}
