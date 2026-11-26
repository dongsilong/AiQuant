package org.dromara.quant.execution;

import org.dromara.quant.execution.mapper.LiveOrderDispatchMapper;
import org.dromara.quant.runtime.DualHedgePositionCycleKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class StrategyPreDispatchIntentServiceTest {
    private final LiveOrderDispatchMapper mapper = mock(LiveOrderDispatchMapper.class);
    private final StrategyPreDispatchIntentService service = new StrategyPreDispatchIntentService(mapper);

    @Test
    void persistsTestnetLegCycleBeforeOrderDispatch() {
        String cycleKey = DualHedgePositionCycleKey.of("round-1", "LONG");
        when(mapper.insertPreDispatchIntentWithCycle(1L, "BTCUSDT", "round-1:LONG:OPEN", "client-1",
            2L, 3L, "OPEN", "LONG", "BUY", 0L, cycleKey)).thenReturn(1);

        service.recordTestnetOpenIntent(1L, 2L, 3L, "btcusdt", "round-1:LONG:OPEN",
            "client-1", "LONG", "BUY", cycleKey);

        verify(mapper).insertPreDispatchIntentWithCycle(1L, "BTCUSDT", "round-1:LONG:OPEN", "client-1",
            2L, 3L, "OPEN", "LONG", "BUY", 0L, cycleKey);
    }

    @Test
    void invalidLegCycleOrDirectionIsRejectedBeforeDatabaseWrite() {
        String shortCycle = DualHedgePositionCycleKey.of("round-1", "SHORT");

        assertThrows(IllegalArgumentException.class, () -> service.recordTestnetOpenIntent(
            1L, 2L, 3L, "BTCUSDT", "round-1:LONG:OPEN", "client-1", "LONG", "BUY", shortCycle));
        assertThrows(IllegalArgumentException.class, () -> service.recordTestnetOpenIntent(
            1L, 2L, 3L, "BTCUSDT", "round-1:LONG:OPEN", "client-1", "LONG", "SELL",
            DualHedgePositionCycleKey.of("round-1", "LONG")));
        verifyNoInteractions(mapper);
    }

    @Test
    void failedPreDispatchWritePreventsCallerFromProceeding() {
        String cycleKey = DualHedgePositionCycleKey.of("round-1", "LONG");
        when(mapper.insertPreDispatchIntentWithCycle(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(0);

        assertThrows(IllegalStateException.class, () -> service.recordTestnetOpenIntent(
            1L, 2L, 3L, "BTCUSDT", "round-1:LONG:OPEN", "client-1", "LONG", "BUY", cycleKey));
    }
}
