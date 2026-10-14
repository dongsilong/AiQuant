package org.dromara.quant.reconcile;

import org.dromara.quant.config.QuantProperties;
import org.dromara.quant.credential.domain.QuantCredential;
import org.dromara.quant.credential.mapper.QuantCredentialMapper;
import org.dromara.quant.market.UserDataStreamHub;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** LIVE private events are forwarded by the lease-owning Hub, never this legacy bridge. */
@Tag("dev")
class TradingUserDataEventBridgeTest {
    @Test
    void liveAccountIsNotSubscribedByUnownedLegacyBridge() {
        var mapper = mock(QuantCredentialMapper.class);
        var hub = mock(UserDataStreamHub.class);
        var reconciliation = mock(TradingReconciliationService.class);
        var properties = new QuantProperties();
        var account = new QuantCredential();
        account.setId(110L);
        account.setRunMode("LIVE");
        account.setStatus("0");
        when(mapper.selectList(any())).thenReturn(List.of(account));

        var bridge = new TradingUserDataEventBridge(mapper, hub, reconciliation, properties);
        bridge.supervise();
        bridge.supervise();

        verifyNoInteractions(hub);
        verifyNoInteractions(reconciliation);
    }
}
