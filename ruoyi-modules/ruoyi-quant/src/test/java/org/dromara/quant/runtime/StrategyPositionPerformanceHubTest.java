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

        StrategyPositionPerformanceEvent event = new StrategyPositionPerformanceEvent(
            11L,          // 1  credentialId
            21L,          // 2  instanceId
            31L,          // 3  positionId
            "cycle",      // 4  positionCycleKey
            "BTCUSDT",    // 5  symbol
            "KlineSlope", // 6  pluginCode      ← 原来的 "LONG" 挪走
            "LONG",       // 7  direction       ← 原来的 null 改成 "LONG"
            null,         // 8  entryPrice
            null,         // 9  initialPositionQty
            null,         // 10 initialMargin
            null,         // 11 highestPriceSinceEntry
            null,         // 12 lowestPriceSinceEntry
            null,         // 13 maximumReturnRate
            null,         // 14 highestStep
            null,         // 15 confirmedProtectionStep
            null,         // 16 frozenLeverage
            null,         // 17 ladderMaximumReturnRate
            null          // 18 updatedAt
        );
        hub.publish(event);
        subscription.close();
        hub.publish(event);

        assertEquals(List.of(event), first);
        assertEquals(List.of(), second);
    }
}
