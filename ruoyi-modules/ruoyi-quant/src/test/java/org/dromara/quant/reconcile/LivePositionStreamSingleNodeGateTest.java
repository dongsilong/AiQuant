package org.dromara.quant.reconcile;

import org.dromara.quant.credential.mapper.QuantCredentialMapper;
import org.dromara.quant.exchange.ExchangeGateway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

/** Additional user-data stream must not be enabled accidentally in a cluster. */
@Tag("dev")
class LivePositionStreamSingleNodeGateTest {
    @Test
    void dedicatedStreamsNeverSubscribeWithoutExplicitSingleNodeOptIn() {
        var credentials = mock(QuantCredentialMapper.class);
        var gateway = mock(ExchangeGateway.class);
        var events = mock(LivePositionEventCoordinator.class);
        var freshness = new LivePositionFreshness();
        var stream = new LivePositionStreamTriggers(credentials, gateway, events, freshness);
        stream.supervise();
        verifyNoInteractions(credentials, gateway, events);
    }
}
