package org.dromara.quant.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StrategyPositionPerformanceHubTest {
    @Test
    void onlyPublishesToListenersForMatchingCredentialAndStopsAfterUnsubscribe() throws Exception {
        StrategyPositionPerformanceHub hub = new StrategyPositionPerformanceHub();
        List<StrategyPositionPerformanceEvent> first = new ArrayList<>();
        List<StrategyPositionPerformanceEvent> second = new ArrayList<>();
        AutoCloseable subscription = hub.subscribe(11L, first::add);
        hub.subscribe(12L, second::add);

        StrategyPositionPerformanceEvent event = new StrategyPositionPerformanceEvent(11L, 21L, 31L, "cycle", "BTCUSDT", "LONG", null, null, null, null, null, null);
        hub.publish(event);
        subscription.close();
        hub.publish(event);

        assertEquals(List.of(event), first);
        assertEquals(List.of(), second);
    }
}
