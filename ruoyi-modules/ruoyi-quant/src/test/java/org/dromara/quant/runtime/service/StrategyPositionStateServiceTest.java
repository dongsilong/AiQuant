package org.dromara.quant.runtime.service;

import org.dromara.quant.runtime.domain.QuantStrategyPositionState;
import org.dromara.quant.runtime.mapper.QuantStrategyPositionStateMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@org.junit.jupiter.api.Tag("dev")
class StrategyPositionStateServiceTest {
    private final QuantStrategyPositionStateMapper mapper = mock(QuantStrategyPositionStateMapper.class);
    private final StrategyPositionStateService service = new StrategyPositionStateService(mapper);

    @Test
    void rejectedUpdateRestoresVersionMutatedByOptimisticLockInterceptor() {
        var state = state("OPEN", null, false, false);
        state.setVersion(7);
        when(mapper.updateById(any(QuantStrategyPositionState.class))).thenAnswer(call -> {
            state.setVersion(8);
            return 0;
        });
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> service.save(state));
        assertEquals(7, state.getVersion(), "failed SQL must not leave an uncommitted version on the runtime object");
    }

    @Test
    void closedProtectionCleanupQueryReturnsMapperResults() {
        var pending = state("CLOSED", null, true, true);
        pending.setPositionQty(BigDecimal.ZERO);
        pending.setPositionId(7L);
        pending.setPositionCycleKey("cycle");
        when(mapper.selectList(any())).thenReturn(List.of(pending));

        assertEquals(List.of(pending), service.findClosedProtectionCleanupPending());
        verify(mapper).selectList(any());
    }

    @Test
    void flatSnapshotClosesOpenQuantityButPreservesRecoveryFence() {
        var cleanOpen = state("OPEN", null, false, false);
        var opening = state("OPEN_PENDING", "OPEN", false, false);
        var recovery = state("OPEN", null, true, false);
        var cleanup = state("PROTECTION_CLEANUP", null, false, false);
        when(mapper.selectList(any())).thenReturn(List.of(cleanOpen, opening, recovery, cleanup));
        when(mapper.updateById(any(QuantStrategyPositionState.class))).thenReturn(1);

        service.markClosedByPositionId(7L);

        assertEquals("CLOSED", cleanOpen.getStatus());
        assertEquals(BigDecimal.ZERO, cleanOpen.getPositionQty());
        assertEquals(true, cleanOpen.getRecoveryRequired(), "snapshot closure must wait for a durable close-candle fence");
        assertEquals(true, cleanOpen.getUnsafeRecovery(), "entries must remain blocked until the close candle is fenced and protections are checked");
        assertEquals("OPEN_PENDING", opening.getStatus());
        assertEquals("OPEN", opening.getPendingAction());
        assertEquals("CLOSED", recovery.getStatus(), "a flat account snapshot must close stale OPEN quantity even when recovery remains required");
        assertEquals(BigDecimal.ZERO, recovery.getPositionQty());
        assertEquals(true, recovery.getRecoveryRequired(), "closing stale quantity must not clear unresolved recovery");
        assertEquals(true, recovery.getUnsafeRecovery(), "closing stale quantity must not release the entry gate");
        assertEquals("PROTECTION_CLEANUP", cleanup.getStatus());
        verify(mapper, times(1)).updateById(cleanOpen);
        verify(mapper, times(1)).updateById(recovery);
    }

    private QuantStrategyPositionState state(String status, String pendingAction, boolean recoveryRequired, boolean unsafeRecovery) {
        var state = new QuantStrategyPositionState();
        state.setId(1L);
        state.setInstanceId(2L);
        state.setStatus(status);
        state.setPositionQty(BigDecimal.ONE);
        state.setPendingAction(pendingAction);
        state.setRecoveryRequired(recoveryRequired);
        state.setUnsafeRecovery(unsafeRecovery);
        return state;
    }
}
