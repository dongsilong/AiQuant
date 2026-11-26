package org.dromara.quant.runtime.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.dromara.quant.account.mapper.QuantTradeMapper;
import org.dromara.quant.exchange.AccountPositionSnapshotQuery;
import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.execution.ExecutionRecordService;
import org.dromara.quant.execution.OrderLedgerService;
import org.dromara.quant.execution.domain.QuantExecutionRecord;
import org.dromara.quant.order.mapper.QuantOrderMapper;
import org.dromara.quant.runtime.domain.QuantStrategyHedgeRound;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class DualHedgeOrderFactRecoveryServiceTest {
    private final StrategyHedgeRoundService roundService = mock(StrategyHedgeRoundService.class);
    private final ExecutionRecordService executionRecordService = mock(ExecutionRecordService.class);
    private final QuantOrderMapper orderMapper = mock(QuantOrderMapper.class);
    private final QuantTradeMapper tradeMapper = mock(QuantTradeMapper.class);
    private final OrderLedgerService orderLedgerService = mock(OrderLedgerService.class);
    private final ExchangeGateway gateway = mock(ExchangeGateway.class);
    private final AccountPositionSnapshotQuery positionSnapshotQuery = mock(AccountPositionSnapshotQuery.class);
    private final StrategyPositionStateService positionStateService = mock(StrategyPositionStateService.class);
    private final DualHedgeOrderFactRecoveryService service = new DualHedgeOrderFactRecoveryService(
        roundService, executionRecordService, orderMapper, tradeMapper, orderLedgerService, gateway,
        positionSnapshotQuery, positionStateService);
    private final ExchangeGateway.Credential credential =
        new ExchangeGateway.Credential("api", "secret", "HMAC", "TESTNET");

    @Test
    void unknownLegsRemainUnknownAndNeverTriggerOrderMutation() {
        QuantStrategyHedgeRound round = round();
        when(positionSnapshotQuery.query(3L, credential)).thenReturn(List.of());
        when(executionRecordService.find(3L, "round-1:LONG:OPEN")).thenReturn(execution("round-1:LONG:OPEN", "UNKNOWN"));
        when(executionRecordService.find(3L, "round-1:SHORT:OPEN")).thenReturn(execution("round-1:SHORT:OPEN", "UNKNOWN"));
        when(orderMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(gateway.queryOrder(eq(credential), eq("BTCUSDT"), isNull(), anyString())).thenReturn(null);

        var result = service.inspect(round, credential);

        assertEquals(DualHedgeLegStateResolver.UNKNOWN, result.longLegStatus());
        assertEquals(DualHedgeLegStateResolver.UNKNOWN, result.shortLegStatus());
        assertEquals(StrategyHedgeRoundService.STATUS_DEGRADED, result.roundStatus());
        verify(roundService).save(round);
        verifyNoInteractions(orderLedgerService);
        verify(gateway, never()).placeOrder(any());
        verify(gateway, never()).cancelOrder(any());
    }

    @Test
    void marksLegsOpenOnlyWhenFilledOrdersMatchBothCurrentPositions() {
        QuantStrategyHedgeRound round = round();
        when(positionSnapshotQuery.query(3L, credential)).thenReturn(List.of(
            position("LONG", "2"), position("SHORT", "2")));
        when(executionRecordService.find(3L, "round-1:LONG:OPEN")).thenReturn(execution("round-1:LONG:OPEN", "CONFIRMED"));
        when(executionRecordService.find(3L, "round-1:SHORT:OPEN")).thenReturn(execution("round-1:SHORT:OPEN", "CONFIRMED"));
        when(orderMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(tradeMapper.selectList(any(Wrapper.class))).thenReturn(
            List.of(trade(101L, "LONG", "BUY")), List.of(trade(101L, "LONG", "BUY")),
            List.of(trade(102L, "SHORT", "SELL")), List.of(trade(102L, "SHORT", "SELL")));
        when(gateway.queryOrder(credential, "BTCUSDT", 101L, "client-long")).thenReturn(order(101L, "client-long", "BUY", "LONG"));
        when(gateway.queryOrder(credential, "BTCUSDT", 102L, "client-short")).thenReturn(order(102L, "client-short", "SELL", "SHORT"));
        when(orderLedgerService.syncSnapshot(eq(3L), eq("BTCUSDT"), any(ExchangeGateway.OrderSnapshot.class)))
            .thenAnswer(invocation -> ledgerOrder(invocation.getArgument(2)));
        when(orderMapper.mergeExchangeFactIfMonotonic(any())).thenReturn(1);
        when(positionStateService.findByInstanceAndLeg(2L, "LONG")).thenReturn(null);
        when(positionStateService.findByInstanceAndLeg(2L, "SHORT")).thenReturn(null);

        var result = service.inspect(round, credential);

        assertEquals(DualHedgeLegStateResolver.OPEN, result.longLegStatus());
        assertEquals(DualHedgeLegStateResolver.OPEN, result.shortLegStatus());
        assertEquals(StrategyHedgeRoundService.STATUS_OPEN, result.roundStatus());
        verify(orderLedgerService).syncSnapshot(eq(3L), eq("BTCUSDT"),
            argThat(snapshot -> snapshot != null && Long.valueOf(101L).equals(snapshot.orderId())));
        verify(orderLedgerService).syncSnapshot(eq(3L), eq("BTCUSDT"),
            argThat(snapshot -> snapshot != null && Long.valueOf(102L).equals(snapshot.orderId())));
        verify(executionRecordService).markRecovered(any(QuantExecutionRecord.class), eq(101L), eq("FILLED"));
        verify(executionRecordService).markRecovered(any(QuantExecutionRecord.class), eq(102L), eq("FILLED"));
        verify(orderMapper, times(2)).mergeExchangeFactIfMonotonic(any());
        var recoveredStates = org.mockito.ArgumentCaptor.forClass(org.dromara.quant.runtime.domain.QuantStrategyPositionState.class);
        verify(positionStateService, times(2)).save(recoveredStates.capture());
        assertEquals(List.of("LONG", "SHORT"), recoveredStates.getAllValues().stream()
            .map(org.dromara.quant.runtime.domain.QuantStrategyPositionState::getLegKey).toList());
        assertEquals(List.of("round-1", "round-1"), recoveredStates.getAllValues().stream()
            .map(org.dromara.quant.runtime.domain.QuantStrategyPositionState::getHedgeRoundKey).toList());
        assertEquals(List.of(
            org.dromara.quant.runtime.DualHedgePositionCycleKey.of("round-1", "LONG"),
            org.dromara.quant.runtime.DualHedgePositionCycleKey.of("round-1", "SHORT")),
            recoveredStates.getAllValues().stream()
                .map(org.dromara.quant.runtime.domain.QuantStrategyPositionState::getPositionCycleKey).toList());
        assertEquals(List.of(true, true), recoveredStates.getAllValues().stream()
            .map(org.dromara.quant.runtime.domain.QuantStrategyPositionState::getUnsafeRecovery).toList());
    }

    @Test
    void doesNotRegressExistingClosingLegState() {
        QuantStrategyHedgeRound round = round();
        when(positionSnapshotQuery.query(3L, credential)).thenReturn(List.of(
            position("LONG", "2"), position("SHORT", "2")));
        when(executionRecordService.find(3L, "round-1:LONG:OPEN")).thenReturn(execution("round-1:LONG:OPEN", "CONFIRMED"));
        when(executionRecordService.find(3L, "round-1:SHORT:OPEN")).thenReturn(execution("round-1:SHORT:OPEN", "CONFIRMED"));
        when(orderMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(tradeMapper.selectList(any(Wrapper.class))).thenReturn(
            List.of(trade(101L, "LONG", "BUY")), List.of(trade(101L, "LONG", "BUY")),
            List.of(trade(102L, "SHORT", "SELL")), List.of(trade(102L, "SHORT", "SELL")));
        when(gateway.queryOrder(credential, "BTCUSDT", 101L, "client-long")).thenReturn(order(101L, "client-long", "BUY", "LONG"));
        when(gateway.queryOrder(credential, "BTCUSDT", 102L, "client-short")).thenReturn(order(102L, "client-short", "SELL", "SHORT"));
        when(orderLedgerService.syncSnapshot(eq(3L), eq("BTCUSDT"), any(ExchangeGateway.OrderSnapshot.class)))
            .thenAnswer(invocation -> ledgerOrder(invocation.getArgument(2)));
        when(orderMapper.mergeExchangeFactIfMonotonic(any())).thenReturn(1);
        var closing = new org.dromara.quant.runtime.domain.QuantStrategyPositionState();
        closing.setInstanceId(2L);
        closing.setLegKey("LONG");
        closing.setHedgeRoundKey("round-1");
        closing.setStatus("CLOSING");
        when(positionStateService.findByInstanceAndLeg(2L, "LONG")).thenReturn(closing);

        assertThrows(IllegalStateException.class, () -> service.inspect(round, credential));

        assertEquals("CLOSING", closing.getStatus());
        verify(positionStateService, never()).save(any());
        verify(roundService, never()).save(any());
    }

    @Test
    void filledOrdersWithoutCompleteTradeFactsDoNotCreateStrategyPositionState() {
        QuantStrategyHedgeRound round = round();
        when(positionSnapshotQuery.query(3L, credential)).thenReturn(List.of(
            position("LONG", "2"), position("SHORT", "2")));
        when(executionRecordService.find(3L, "round-1:LONG:OPEN")).thenReturn(execution("round-1:LONG:OPEN", "CONFIRMED"));
        when(executionRecordService.find(3L, "round-1:SHORT:OPEN")).thenReturn(execution("round-1:SHORT:OPEN", "CONFIRMED"));
        when(orderMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(tradeMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(gateway.queryOrder(credential, "BTCUSDT", 101L, "client-long")).thenReturn(order(101L, "client-long", "BUY", "LONG"));
        when(gateway.queryOrder(credential, "BTCUSDT", 102L, "client-short")).thenReturn(order(102L, "client-short", "SELL", "SHORT"));
        when(orderLedgerService.syncSnapshot(eq(3L), eq("BTCUSDT"), any(ExchangeGateway.OrderSnapshot.class)))
            .thenAnswer(invocation -> ledgerOrder(invocation.getArgument(2)));
        when(orderMapper.mergeExchangeFactIfMonotonic(any())).thenReturn(1);

        var result = service.inspect(round, credential);

        assertEquals(DualHedgeLegStateResolver.FILLED_POSITION_UNVERIFIED, result.longLegStatus());
        assertEquals(DualHedgeLegStateResolver.FILLED_POSITION_UNVERIFIED, result.shortLegStatus());
        verify(positionStateService, never()).save(any());
    }

    @Test
    void refusesSignalReadyRoundWithoutQueryingExchange() {
        QuantStrategyHedgeRound round = round();
        round.setStatus(StrategyHedgeRoundService.STATUS_SIGNAL_READY);

        assertThrows(IllegalStateException.class, () -> service.inspect(round, credential));

        verifyNoInteractions(positionSnapshotQuery, gateway, executionRecordService, roundService);
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
        round.setEntryEquitySnapshot(new BigDecimal("1000"));
        round.setTargetMarginPerLeg(new BigDecimal("100"));
        round.setLeverage(10);
        round.setSignalEventTime(java.time.LocalDateTime.now());
        round.setStatus(StrategyHedgeRoundService.STATUS_OPENING);
        round.setLongLegStatus(StrategyHedgeRoundService.LEG_DISPATCHING);
        round.setShortLegStatus(StrategyHedgeRoundService.LEG_DISPATCHING);
        round.setLongIdempotencyKey("round-1:LONG:OPEN");
        round.setShortIdempotencyKey("round-1:SHORT:OPEN");
        round.setLongClientOrderId("client-long");
        round.setShortClientOrderId("client-short");
        round.setLongOrderId(101L);
        round.setShortOrderId(102L);
        return round;
    }

    private static QuantExecutionRecord execution(String key, String status) {
        QuantExecutionRecord record = new QuantExecutionRecord();
        record.setCredentialId(3L);
        record.setStrategyId(1L);
        record.setStrategyInstanceId(2L);
        record.setOperationType("OPEN");
        record.setSymbol("BTCUSDT");
        record.setIdempotencyKey(key);
        record.setClientOrderId(key.endsWith(":LONG:OPEN") ? "client-long" : "client-short");
        record.setExchangeOrderId(key.endsWith(":LONG:OPEN") ? 101L : 102L);
        record.setExecutionStatus(status);
        return record;
    }

    private static org.dromara.quant.order.domain.QuantOrder ledgerOrder(ExchangeGateway.OrderSnapshot snapshot) {
        var order = new org.dromara.quant.order.domain.QuantOrder();
        order.setId(snapshot.orderId());
        order.setCredentialId(3L);
        order.setSymbol(snapshot.symbol());
        order.setExchangeOrderId(snapshot.orderId());
        order.setClientOrderId(snapshot.clientOrderId());
        order.setPositionSide(snapshot.positionSide());
        return order;
    }

    private static org.dromara.quant.account.domain.QuantTrade trade(Long orderId, String positionSide, String side) {
        var trade = new org.dromara.quant.account.domain.QuantTrade();
        trade.setCredentialId(3L);
        trade.setSymbol("BTCUSDT");
        trade.setExchangeOrderId(orderId);
        trade.setExchangeTradeId(orderId + 1000L);
        trade.setPositionSide(positionSide);
        trade.setSide(side);
        trade.setQuantity(new BigDecimal("2"));
        trade.setPrice(new BigDecimal("100"));
        trade.setCommission(BigDecimal.ZERO);
        trade.setCommissionAsset("USDT");
        trade.setRealizedPnl(BigDecimal.ZERO);
        trade.setTradeTime(java.time.LocalDateTime.now());
        return trade;
    }

    private static ExchangeGateway.OrderSnapshot order(Long id, String clientId, String side, String positionSide) {
        return new ExchangeGateway.OrderSnapshot("BTCUSDT", id, clientId, side, "MARKET", "FILLED",
            BigDecimal.ZERO, new BigDecimal("2"), new BigDecimal("2"), Instant.now(),
            new BigDecimal("100"), positionSide, false, Instant.now(), false);
    }

    private static ExchangeGateway.PositionSnapshot position(String side, String quantity) {
        return new ExchangeGateway.PositionSnapshot("BTCUSDT", side, new BigDecimal(quantity),
            new BigDecimal("100"), new BigDecimal("100"), BigDecimal.ZERO, null, BigDecimal.ZERO,
            10, null, null, Instant.now());
    }
}
