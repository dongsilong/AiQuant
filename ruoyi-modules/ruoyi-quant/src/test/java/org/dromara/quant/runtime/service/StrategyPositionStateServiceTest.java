package org.dromara.quant.runtime.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.dromara.quant.runtime.PositionProtectionState;
import org.dromara.quant.runtime.domain.QuantStrategyPositionState;
import org.dromara.quant.runtime.mapper.QuantStrategyPositionStateMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class StrategyPositionStateServiceTest {
    private final QuantStrategyPositionStateMapper mapper = mock(QuantStrategyPositionStateMapper.class);
    private final StrategyPositionStateService service = new StrategyPositionStateService(mapper);

    @Test
    void flatSnapshotClosesOnlyCleanOpenState() {
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
        assertEquals("OPEN", recovery.getStatus());
        assertEquals("PROTECTION_CLEANUP", cleanup.getStatus());
        verify(mapper, times(1)).updateById(cleanOpen);
    }

    @Test
    void findUnclosedQueryIncludesClosedRowsAwaitingRecovery() {
        var configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "unclosed-test"), QuantStrategyPositionState.class);
        when(mapper.selectList(any())).thenReturn(List.of());

        service.findUnclosed();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<QuantStrategyPositionState>> captor =
            (ArgumentCaptor<LambdaQueryWrapper<QuantStrategyPositionState>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectList(captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertTrue(sql.contains("recovery_required"), sql);
        assertTrue(sql.contains("unsafe_recovery"), sql);
        assertTrue(sql.contains("CLOSED"), sql);
    }

    @Test
    void startingANewPositionCycleClearsOldTakeProfitAndRecoveryState() {
        var state = state("CLOSED", null, true, true);
        state.setPositionCycleKey("old-cycle");
        state.setPositionId(7L);
        state.setDirection("LONG");
        state.setEntryPrice(new BigDecimal("100"));
        state.setEntryAtr(new BigDecimal("2"));
        state.setInitialPositionQty(new BigDecimal("10"));
        state.setInitialMargin(new BigDecimal("100"));
        state.setFrozenStopLossRatio(new BigDecimal("0.05"));
        state.setStrategyParamsSnapshot("{\"old\":true}");
        state.setFirstTakeProfitTargetQty(new BigDecimal("5"));
        state.setFirstTakeProfitExecutedQty(new BigDecimal("5"));
        state.setFirstTakeProfitLocalExecutedQty(new BigDecimal("5"));
        state.setFirstTakeProfitTriggered(true);
        state.setHighestStep(4);
        state.setConfirmedProtectionStep(3);
        state.setPendingAction("CLOSE");
        state.setPendingIdempotencyKey("old-key");
        state.setPendingOrderId(9L);
        state.setPendingClientOrderId("old-client");
        state.setPendingOrderTime(10L);
        state.setPendingNotFoundConfirmations(2);

        PositionProtectionState.startCycle(state, 5, new BigDecimal("0.02"), new BigDecimal("101"));

        assertFalse(state.getFirstTakeProfitTriggered());
        assertEquals(0, state.getHighestStep());
        assertEquals(0, state.getConfirmedProtectionStep());
        assertEquals(BigDecimal.ZERO, state.getFirstTakeProfitExecutedQty());
        assertEquals(BigDecimal.ZERO, state.getFirstTakeProfitLocalExecutedQty());
        assertNull(state.getFirstTakeProfitTargetQty());
        assertFalse(state.getRecoveryRequired());
        assertFalse(state.getUnsafeRecovery());
        assertNull(state.getPendingAction());
        assertNull(state.getPendingIdempotencyKey());
        assertNull(state.getPendingOrderId());
        assertNull(state.getPendingClientOrderId());
        assertNull(state.getPendingOrderTime());
        assertEquals(0, state.getPendingNotFoundConfirmations());
        assertEquals(BigDecimal.ZERO, state.getInitialPositionQty());
        assertEquals(BigDecimal.ZERO, state.getInitialMargin());
        assertNull(state.getEntryAtr());
        assertNull(state.getFrozenStopLossRatio());
        assertNull(state.getStrategyParamsSnapshot());
        assertNull(state.getPositionId());
        assertNull(state.getDirection());
        assertEquals(new BigDecimal("101"), state.getHighestPriceSinceEntry());
        assertEquals(new BigDecimal("101"), state.getLowestPriceSinceEntry());
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
