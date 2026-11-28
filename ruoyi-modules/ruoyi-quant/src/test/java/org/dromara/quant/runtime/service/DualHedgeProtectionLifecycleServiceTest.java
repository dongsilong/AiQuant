package org.dromara.quant.runtime.service;

import org.dromara.quant.credential.domain.QuantCredential;
import org.dromara.quant.position.domain.QuantPosition;
import org.dromara.quant.protection.ProtectionOrderService;
import org.dromara.quant.runtime.domain.QuantStrategyPositionState;
import org.dromara.quant.strategy.ProtectionSignal;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class DualHedgeProtectionLifecycleServiceTest {
    private final StrategyHedgeRoundService roundService = mock(StrategyHedgeRoundService.class);
    private final DualHedgeOrderFactRecoveryService recoveryService = mock(DualHedgeOrderFactRecoveryService.class);
    private final StrategyPositionStateService stateService = mock(StrategyPositionStateService.class);
    private final org.dromara.quant.position.mapper.QuantPositionMapper positionMapper =
        mock(org.dromara.quant.position.mapper.QuantPositionMapper.class);
    private final org.dromara.quant.credential.mapper.QuantCredentialMapper credentialMapper =
        mock(org.dromara.quant.credential.mapper.QuantCredentialMapper.class);
    private final org.dromara.quant.execution.TradeExecutionApplicationService executionService =
        mock(org.dromara.quant.execution.TradeExecutionApplicationService.class);
    private final ProtectionOrderService protectionService = mock(ProtectionOrderService.class);

    private final DualHedgeProtectionLifecycleService service = new DualHedgeProtectionLifecycleService(
        roundService, recoveryService, stateService, positionMapper, credentialMapper, executionService, protectionService);

    @Test
    void installsHardStopFivePercentPartialTakeProfitAndBreakEvenStopPerLeg() {
        QuantStrategyPositionState state = state();
        QuantPosition position = position();
        QuantCredential credential = new QuantCredential();
        credential.setId(3L);
        credential.setRunMode("TESTNET");
        when(executionService.requireCredential(3L)).thenReturn(credential);
        when(protectionService.ensureMarginHardStop(eq(3L), any(QuantPosition.class),
            eq(new BigDecimal("0.03")), eq(true))).thenReturn(new org.dromara.quant.protection.domain.QuantProtectionOrder());

        service.ensureExchangeProtections(state, position);

        var positionCaptor = org.mockito.ArgumentCaptor.forClass(QuantPosition.class);
        verify(protectionService).ensureMarginHardStop(eq(3L), positionCaptor.capture(),
            eq(new BigDecimal("0.03")), eq(true));
        assertEquals("round-1:LONG", positionCaptor.getValue().getProtectionCycleKey());
        assertEquals(3, positionCaptor.getValue().getProtectionPolicyVersion());
        var signalCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(protectionService).syncStrategyProtections(eq(3L), eq(positionCaptor.getValue()),
            eq(new BigDecimal("2")), signalCaptor.capture(), isNull(), eq(BigDecimal.ZERO), eq(true));
        List<ProtectionSignal> signals = signalCaptor.getValue();
        assertEquals(List.of("FIRST_TP", "STEP_TP"), signals.stream().map(ProtectionSignal::type).toList());
        assertEquals(new BigDecimal("0.50"), signals.getFirst().quantityRate());
        assertEquals(ProtectionSignal.QuantityBase.INITIAL, signals.getFirst().quantityBase());
        assertEquals("TAKE_PROFIT_MARKET", signals.getFirst().orderType());
        assertEquals(0, signals.get(1).stepIndex());
    }

    @Test
    void hardStopFailurePreventsOtherProtectionOrdersFromBeingReportedReady() {
        QuantStrategyPositionState state = state();
        QuantPosition position = position();
        QuantCredential credential = new QuantCredential();
        credential.setId(3L);
        credential.setRunMode("TESTNET");
        when(executionService.requireCredential(3L)).thenReturn(credential);
        when(protectionService.ensureMarginHardStop(any(), any(), any(), anyBoolean()))
            .thenThrow(new IllegalStateException("unconfirmed"));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
            () -> service.ensureExchangeProtections(state, position));

        verify(protectionService, never()).syncStrategyProtections(any(), any(), any(), anyList(), any(), any(), anyBoolean());
    }

    private static QuantStrategyPositionState state() {
        QuantStrategyPositionState state = new QuantStrategyPositionState();
        state.setInstanceId(2L);
        state.setLegKey("LONG");
        state.setHedgeRoundKey("round-1");
        state.setPositionCycleKey("round-1:LONG");
        state.setPositionId(201L);
        state.setStatus("OPEN");
        state.setDirection("LONG");
        state.setEntryPrice(new BigDecimal("100"));
        state.setPositionQty(new BigDecimal("2"));
        state.setInitialPositionQty(new BigDecimal("2"));
        state.setInitialMargin(new BigDecimal("20"));
        state.setFrozenLeverage(10);
        state.setFirstTakeProfitLocalExecutedQty(BigDecimal.ZERO);
        return state;
    }

    private static QuantPosition position() {
        QuantPosition position = new QuantPosition();
        position.setId(201L);
        position.setCredentialId(3L);
        position.setSymbol("BTCUSDT");
        position.setPositionSide("LONG");
        position.setEntryPrice(new BigDecimal("100"));
        position.setQuantity(new BigDecimal("2"));
        position.setLeverage(10);
        position.setMargin(new BigDecimal("20"));
        return position;
    }
}
