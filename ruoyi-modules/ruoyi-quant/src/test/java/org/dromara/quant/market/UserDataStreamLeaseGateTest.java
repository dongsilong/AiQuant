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
}
