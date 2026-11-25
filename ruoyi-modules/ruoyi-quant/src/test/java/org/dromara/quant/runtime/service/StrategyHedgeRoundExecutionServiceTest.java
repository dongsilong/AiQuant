package org.dromara.quant.runtime.service;

import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.execution.ExecutionResult;
import org.dromara.quant.execution.TradeExecutionApplicationService;
import org.dromara.quant.execution.TradingPreparationService;
import org.dromara.quant.runtime.domain.QuantStrategyHedgeRound;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class StrategyHedgeRoundExecutionServiceTest {
    private final StrategyHedgeRoundService roundService = mock(StrategyHedgeRoundService.class);
    private final TradeExecutionApplicationService executionService = mock(TradeExecutionApplicationService.class);
    private final TradingPreparationService preparationService = mock(TradingPreparationService.class);
    private final StrategyHedgeRoundExecutionService service =
        new StrategyHedgeRoundExecutionService(roundService, executionService, preparationService);

    @Test
    void dispatchesLongThenShortWithIndependentKeys() {
        QuantStrategyHedgeRound round = round();
        when(preparationService.requireReady(3L, "BTCUSDT", 10, "ISOLATED"))
            .thenReturn(new TradingPreparationService.Prepared(new ExchangeGateway.PositionMode(true), null, Long.MAX_VALUE));
        when(executionService.openByRuntime(eq(3L), eq(1L), eq(2L), eq("BTCUSDT"), eq("BUY"), eq("LONG"),
            any(), eq(new BigDecimal("100")), eq(10), eq("ISOLATED"), eq("round-1:LONG:OPEN"), any(), isNull()))
            .thenReturn(new ExecutionResult(true, "NEW", 101L, "client-long"));
        when(executionService.openByRuntime(eq(3L), eq(1L), eq(2L), eq("BTCUSDT"), eq("SELL"), eq("SHORT"),
            any(), eq(new BigDecimal("100")), eq(10), eq("ISOLATED"), eq("round-1:SHORT:OPEN"), any(), isNull()))
            .thenReturn(new ExecutionResult(true, "NEW", 102L, "client-short"));

        var result = service.dispatchOpening(round, new BigDecimal("100"), Instant.parse("2026-11-25T07:00:00Z"), null);

        assertEquals("BOTH_ACCEPTED_PENDING_FILL", result.status());
        assertEquals(StrategyHedgeRoundService.LEG_ACCEPTED, round.getLongLegStatus());
        assertEquals(StrategyHedgeRoundService.LEG_ACCEPTED, round.getShortLegStatus());
        assertEquals(101L, round.getLongOrderId());
        assertEquals(102L, round.getShortOrderId());
        verify(roundService, times(4)).save(round);
        var inOrder = inOrder(executionService);
        inOrder.verify(executionService).openByRuntime(eq(3L), eq(1L), eq(2L), eq("BTCUSDT"), eq("BUY"), eq("LONG"),
            any(), eq(new BigDecimal("100")), eq(10), eq("ISOLATED"), eq("round-1:LONG:OPEN"), any(), isNull());
        inOrder.verify(executionService).openByRuntime(eq(3L), eq(1L), eq(2L), eq("BTCUSDT"), eq("SELL"), eq("SHORT"),
            any(), eq(new BigDecimal("100")), eq(10), eq("ISOLATED"), eq("round-1:SHORT:OPEN"), any(), isNull());
    }

    @Test
    void unknownLongStopsShortDispatch() {
        QuantStrategyHedgeRound round = round();
        when(preparationService.requireReady(3L, "BTCUSDT", 10, "ISOLATED"))
            .thenReturn(new TradingPreparationService.Prepared(new ExchangeGateway.PositionMode(true), null, Long.MAX_VALUE));
        when(executionService.openByRuntime(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenThrow(new IllegalStateException("timeout"));

        var result = service.dispatchOpening(round, new BigDecimal("100"), Instant.parse("2026-11-25T07:00:00Z"), null);

        assertEquals(StrategyHedgeRoundService.STATUS_DEGRADED, result.status());
        assertEquals(StrategyHedgeRoundService.LEG_UNKNOWN, round.getLongLegStatus());
        assertEquals(StrategyHedgeRoundService.LEG_NOT_STARTED, round.getShortLegStatus());
        verify(executionService, times(1)).openByRuntime(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void unknownShortPreservesAcceptedLongAndDegradesRound() {
        QuantStrategyHedgeRound round = round();
        when(preparationService.requireReady(3L, "BTCUSDT", 10, "ISOLATED"))
            .thenReturn(new TradingPreparationService.Prepared(new ExchangeGateway.PositionMode(true), null, Long.MAX_VALUE));
        when(executionService.openByRuntime(eq(3L), eq(1L), eq(2L), eq("BTCUSDT"), eq("BUY"), eq("LONG"),
            any(), eq(new BigDecimal("100")), eq(10), eq("ISOLATED"), eq("round-1:LONG:OPEN"), any(), isNull()))
            .thenReturn(new ExecutionResult(true, "NEW", 101L, "client-long"));
        when(executionService.openByRuntime(eq(3L), eq(1L), eq(2L), eq("BTCUSDT"), eq("SELL"), eq("SHORT"),
            any(), eq(new BigDecimal("100")), eq(10), eq("ISOLATED"), eq("round-1:SHORT:OPEN"), any(), isNull()))
            .thenThrow(new IllegalStateException("timeout"));

        var result = service.dispatchOpening(round, new BigDecimal("100"), Instant.parse("2026-11-25T07:00:00Z"), null);

        assertEquals(StrategyHedgeRoundService.STATUS_DEGRADED, result.status());
        assertEquals(StrategyHedgeRoundService.LEG_ACCEPTED, round.getLongLegStatus());
        assertEquals(StrategyHedgeRoundService.LEG_UNKNOWN, round.getShortLegStatus());
        assertEquals(101L, round.getLongOrderId());
    }

    @Test
    void oneWayAccountAbortsBeforeSendingAnyOrder() {
        QuantStrategyHedgeRound round = round();
        when(preparationService.requireReady(3L, "BTCUSDT", 10, "ISOLATED"))
            .thenReturn(new TradingPreparationService.Prepared(new ExchangeGateway.PositionMode(false), null, Long.MAX_VALUE));

        var result = service.dispatchOpening(round, new BigDecimal("100"), Instant.parse("2026-11-25T07:00:00Z"), null);

        assertEquals(StrategyHedgeRoundService.STATUS_ABORTED, result.status());
        assertEquals(StrategyHedgeRoundService.LEG_FAILED, round.getLongLegStatus());
        assertEquals(StrategyHedgeRoundService.LEG_FAILED, round.getShortLegStatus());
        verifyNoInteractions(executionService);
    }

    private static QuantStrategyHedgeRound round() {
        QuantStrategyHedgeRound round = new QuantStrategyHedgeRound();
        round.setId(11L);
        round.setVersion(0);
        round.setHedgeRoundKey("round-1");
        round.setStrategyId(1L);
        round.setInstanceId(2L);
        round.setCredentialId(3L);
        round.setSymbol("BTCUSDT");
        round.setStatus(StrategyHedgeRoundService.STATUS_OPENING);
        round.setLongLegStatus(StrategyHedgeRoundService.LEG_NOT_STARTED);
        round.setShortLegStatus(StrategyHedgeRoundService.LEG_NOT_STARTED);
        round.setTargetNotionalPerLeg(new BigDecimal("1000"));
        round.setLeverage(10);
        return round;
    }
}
