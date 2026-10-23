package org.dromara.quant.runtime.coordination;

import org.dromara.quant.runtime.coordination.mapper.StrategyCloseOwnershipMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@Tag("dev")
class StrategyCloseOwnershipGuardTest {
    private final StrategyCloseOwnershipMapper mapper = mock(StrategyCloseOwnershipMapper.class);
    private final StrategyCloseOwnershipGuard guard = new StrategyCloseOwnershipGuard(mapper);

    private Map<String, Object> ownedExit() {
        var state = new HashMap<String, Object>();
        state.put("status", "EXIT_PENDING");
        state.put("direction", "LONG");
        state.put("position_qty", new BigDecimal("1.2500"));
        state.put("pending_action", "HARD_STOP");
        state.put("pending_idempotency_key", "exit-1");
        state.put("unsafe_recovery", 0);
        return state;
    }

    @Test
    void validPendingStrategyExitMayReduceOnlyItsOwnedQuantity() {
        when(mapper.pendingOwnedExit(13L, 11L, 12L, "BTCUSDT")).thenReturn(ownedExit());
        guard.requireOwnedClose(11L, 12L, 13L, "btcusdt", "SELL", new BigDecimal("1.25"), "exit-1");
        verify(mapper).pendingOwnedExit(13L, 11L, 12L, "BTCUSDT");
    }

    @Test
    void quantityAboveAllocatedShareFailsClosed() {
        when(mapper.pendingOwnedExit(anyLong(), anyLong(), anyLong(), anyString())).thenReturn(ownedExit());
        assertThrows(IllegalStateException.class, () -> guard.requireOwnedClose(11L, 12L, 13L, "BTCUSDT", "SELL", new BigDecimal("1.2501"), "exit-1"));
    }

    @Test
    void unknownOrForeignStrategyStateCannotCloseAccountPosition() {
        assertThrows(IllegalStateException.class, () -> guard.requireOwnedClose(11L, 12L, 999L, "BTCUSDT", "SELL", BigDecimal.ONE, "exit-1"));
        when(mapper.pendingOwnedExit(anyLong(), anyLong(), anyLong(), anyString())).thenReturn(ownedExit());
        assertThrows(IllegalStateException.class, () -> guard.requireOwnedClose(11L, 12L, 13L, "BTCUSDT", "BUY", BigDecimal.ONE, "exit-1"));
        assertThrows(IllegalStateException.class, () -> guard.requireOwnedClose(11L, 12L, 13L, "BTCUSDT", "SELL", BigDecimal.ONE, "unrelated-exit"));
    }

    @Test
    void unsafeRecoveryAndUnconfirmedStateCannotClose() {
        var unsafe = ownedExit();
        unsafe.put("unsafe_recovery", 1);
        when(mapper.pendingOwnedExit(anyLong(), anyLong(), anyLong(), anyString())).thenReturn(unsafe);
        assertThrows(IllegalStateException.class, () -> guard.requireOwnedClose(11L, 12L, 13L, "BTCUSDT", "SELL", BigDecimal.ONE, "exit-1"));
        var noPending = ownedExit();
        noPending.put("status", "OPEN");
        when(mapper.pendingOwnedExit(anyLong(), anyLong(), anyLong(), anyString())).thenReturn(noPending);
        assertThrows(IllegalStateException.class, () -> guard.requireOwnedClose(11L, 12L, 13L, "BTCUSDT", "SELL", BigDecimal.ONE, "exit-1"));
    }
}
