package org.dromara.quant.runtime.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.dromara.quant.runtime.domain.QuantStrategyPositionState;
import org.dromara.quant.runtime.mapper.QuantStrategyPositionStateMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import org.mockito.ArgumentCaptor;
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
