package org.dromara.quant.execution;

import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.account.mapper.QuantTradeMapper;
import org.dromara.quant.execution.mapper.StrategyFillJournalMapper;
import org.dromara.quant.order.domain.QuantOrder;
import org.dromara.quant.order.mapper.QuantOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class StrategyFillJournalServiceTest {
    private final StrategyFillJournalMapper journal = mock(StrategyFillJournalMapper.class);
    private final StrategyFillAllocationService allocations = mock(StrategyFillAllocationService.class);
    private final QuantOrderMapper orders = mock(QuantOrderMapper.class);
    private final StrategyPositionProjectionService projections = mock(StrategyPositionProjectionService.class);
    private final QuantTradeMapper trades = mock(QuantTradeMapper.class);
    private final StrategyPositionRebuildService rebuilds = mock(StrategyPositionRebuildService.class);
    private final StrategyFillJournalService service = new StrategyFillJournalService(journal, allocations, projections, orders, rebuilds);

    @BeforeEach
    void setup() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "trades", trades);
        when(projections.resolveVerifiedAttribution(anyLong(), any(), any())).thenReturn(
            new StrategyPositionProjectionService.VerifiedAttribution(
                StrategyPositionAccounting.PositionSide.LONG, StrategyPositionAccounting.Role.OPEN));
    }

    private ExchangeGateway.TradeSnapshot fill() {
        return new ExchangeGateway.TradeSnapshot("BTCUSDT", 101L, 202L, "BUY", BigDecimal.ONE, BigDecimal.TEN, null, null, Instant.parse("2026-10-17T00:00:00.123Z"), null);
    }

    @Test
    void persistsUnattributedRawFillWithoutReplacingUnknownMoneyFieldsWithZero() {
        when(journal.selectByKey(1L, "BTCUSDT", 101L)).thenReturn(null);
        when(journal.insertRaw(any())).thenReturn(1);
        when(journal.updateStatus(anyLong(), anyString(), any())).thenReturn(1);

        String status = service.attribute(fill(), null, 1L);

        var raw = org.mockito.ArgumentCaptor.forClass(StrategyFillJournalRecord.class);
        verify(journal).insertRaw(raw.capture());
        assertNull(raw.getValue().getCommission());
        assertNull(raw.getValue().getRealizedPnl());
        assertNull(raw.getValue().getCommissionAsset());
        assertEquals("WAITING_ORDER", status);
        verify(journal).updateStatus(anyLong(), eq("WAITING_ORDER"), anyString());
        verifyNoInteractions(allocations);
    }

    @Test
    void keepsRawFactAndMarksMissingAllocationFieldsForRetry() {
        when(journal.selectByKey(1L, "BTCUSDT", 101L)).thenReturn(null);
        when(journal.insertRaw(any())).thenReturn(1);
        when(journal.updateStatus(anyLong(), anyString(), any())).thenReturn(1);
        var order = new QuantOrder();
        order.setCredentialId(1L);
        order.setSymbol("BTCUSDT");
        order.setExchangeOrderId(202L);
        order.setStrategyId(3L);
        order.setStrategyInstanceId(4L);
        doThrow(new IllegalStateException("Binance 成交手续费、盈亏或买卖方向缺失")).when(allocations).allocate(any(), eq(order), eq(1L));

        String status = service.attribute(fill(), order, 1L);

        assertEquals("PENDING_FACTS", status);
        verify(journal).updateStatus(anyLong(), eq("PENDING_FACTS"), contains("手续费"));
    }

    @Test
    void laterCompleteReplayEnrichesPreviouslyUnknownMoneyFactsWithoutConflict() {
        var incoming = new ExchangeGateway.TradeSnapshot("BTCUSDT", 101L, 202L, "BUY", BigDecimal.ONE, BigDecimal.TEN,
            new BigDecimal("0.01"), BigDecimal.ZERO, Instant.parse("2026-10-17T00:00:00.123Z"), "USDT");
        var stored = new StrategyFillJournalRecord();
        stored.setId(77L);
        stored.setCredentialId(1L);
        stored.setSymbol("BTCUSDT");
        stored.setExchangeTradeId(101L);
        stored.setExchangeOrderId(202L);
        stored.setSide("BUY");
        stored.setQuantity(BigDecimal.ONE);
        stored.setPrice(BigDecimal.TEN);
        stored.setTradeTime(java.time.LocalDateTime.ofInstant(incoming.time(), java.time.ZoneOffset.UTC));
        stored.setAttributionStatus("WAITING_ORDER");
        when(journal.selectByKey(1L, "BTCUSDT", 101L)).thenReturn(stored, stored);
        when(journal.enrichMissingFacts(any())).thenReturn(1);
        when(journal.updateStatus(77L, "WAITING_ORDER", "未找到具有明确策略实例归属的本地订单")).thenReturn(1);

        String status = service.attribute(incoming, null, 1L);

        assertEquals("WAITING_ORDER", status);
        var enriched = org.mockito.ArgumentCaptor.forClass(StrategyFillJournalRecord.class);
        verify(journal).enrichMissingFacts(enriched.capture());
        assertEquals(77L, enriched.getValue().getId());
        assertEquals(new BigDecimal("0.01"), enriched.getValue().getCommission());
        assertEquals(BigDecimal.ZERO, enriched.getValue().getRealizedPnl());
        assertEquals("USDT", enriched.getValue().getCommissionAsset());
        verify(journal, never()).insertRaw(any());
        verify(journal, never()).updateStatus(eq(77L), eq("CONFLICT"), anyString());
    }

    @Test
    void conflictingDuplicateIsQuarantinedWithoutOverwritingFirstRawFact() {
        var stored = new StrategyFillJournalRecord();
        stored.setId(77L);
        stored.setCredentialId(1L);
        stored.setSymbol("BTCUSDT");
        stored.setExchangeTradeId(101L);
        stored.setExchangeOrderId(202L);
        stored.setSide("BUY");
        stored.setQuantity(BigDecimal.ONE);
        stored.setPrice(BigDecimal.TEN);
        stored.setTradeTime(java.time.LocalDateTime.ofInstant(fill().time(), java.time.ZoneOffset.UTC));
        when(journal.selectByKey(1L, "BTCUSDT", 101L)).thenReturn(stored);
        when(journal.updateStatus(anyLong(), anyString(), any())).thenReturn(1);
        var conflict = new ExchangeGateway.TradeSnapshot("BTCUSDT", 101L, 202L, "BUY", new BigDecimal("2"), BigDecimal.TEN, null, null, fill().time(), null);

        String status = service.attribute(conflict, null, 1L);

        assertEquals("CONFLICT", status);
        verify(journal, never()).insertRaw(any());
        verify(journal).updateStatus(eq(77L), eq("CONFLICT"), contains("quantity"));
        verifyNoInteractions(allocations);
    }

    @Test
    void crossLedgerConflictIsPersistedWithoutRewritingRawExchangeFact() {
        var stored = new StrategyFillJournalRecord();
        stored.setId(77L);
        stored.setCredentialId(1L);
        stored.setSymbol("BTCUSDT");
        stored.setExchangeTradeId(101L);
        stored.setAttributionStatus("ATTRIBUTED");
        when(journal.selectByKey(1L, "BTCUSDT", 101L)).thenReturn(stored);
        when(journal.updateStatus(77L, "CONFLICT", "local ledger mismatch")).thenReturn(1);

        service.quarantineConflict(1L, "btcusdt", 101L, "local ledger mismatch");

        assertEquals("CONFLICT", stored.getAttributionStatus());
        verify(journal).updateStatus(77L, "CONFLICT", "local ledger mismatch");
        verify(journal, never()).insertRaw(any());
    }

    @Test
    void retriesPersistedFillAfterExactStrategyOrderBecomesAvailable() {
        var fill = new ExchangeGateway.TradeSnapshot("BTCUSDT", 101L, 202L, "BUY", BigDecimal.ONE, BigDecimal.TEN, new BigDecimal("0.01"), BigDecimal.ZERO,
            Instant.parse("2026-10-17T00:00:00.123Z"), "USDT");
        var stored = new StrategyFillJournalRecord();
        stored.setId(77L);
        stored.setCredentialId(1L);
        stored.setSymbol("BTCUSDT");
        stored.setExchangeTradeId(fill.tradeId());
        stored.setExchangeOrderId(fill.orderId());
        stored.setSide(fill.side());
        stored.setQuantity(fill.quantity());
        stored.setPrice(fill.price());
        stored.setCommission(fill.commission());
        stored.setCommissionAsset(fill.commissionAsset());
        stored.setRealizedPnl(fill.realizedPnl());
        stored.setTradeTime(java.time.LocalDateTime.ofInstant(fill.time(), java.time.ZoneOffset.UTC));
        stored.setAttributionStatus("WAITING_ORDER");
        when(journal.selectPending(1L, 100)).thenReturn(java.util.List.of(stored));
        when(journal.selectByKey(1L, "BTCUSDT", fill.tradeId())).thenReturn(stored);
        when(journal.updateStatus(77L, "ATTRIBUTED", null)).thenReturn(1);
        when(trades.backfillStrategyAttribution(1L, "BTCUSDT", 101L, 202L, 3L, 4L)).thenReturn(1);
        var order = new QuantOrder();
        order.setCredentialId(1L);
        order.setSymbol("BTCUSDT");
        order.setExchangeOrderId(202L);
        order.setStrategyId(3L);
        order.setStrategyInstanceId(4L);
        when(orders.selectOne(any())).thenReturn(order);

        assertEquals(1, service.retryPending(1L));

        verify(projections).resolveVerifiedAttribution(1L, order, fill);
        verify(allocations).allocate(fill, order, 1L);
        verify(trades).backfillStrategyAttribution(1L, "BTCUSDT", 101L, 202L, 3L, 4L);
        verify(projections).projectAttributed(eq(1L), eq(order), eq(fill), any(StrategyPositionProjectionService.VerifiedAttribution.class));
        verify(journal).updateStatus(77L, "ATTRIBUTED", null);
    }


    @Test
    void attributedCloseFillInvalidatesAnActiveRebuildBeforeProjection() {
        var closeFill = new ExchangeGateway.TradeSnapshot("BTCUSDT", 101L, 202L, "SELL", BigDecimal.ONE, BigDecimal.TEN,
            BigDecimal.ZERO, BigDecimal.ZERO, Instant.parse("2026-10-17T00:00:00.123Z"), "USDT");
        when(journal.selectByKey(1L, "BTCUSDT", 101L)).thenReturn(null);
        when(journal.insertRaw(any())).thenReturn(1);
        when(journal.updateStatus(anyLong(), anyString(), any())).thenReturn(1);
        when(trades.backfillStrategyAttribution(1L, "BTCUSDT", 101L, 202L, 3L, 4L)).thenReturn(1);
        var order = new QuantOrder();
        order.setCredentialId(1L);
        order.setSymbol("BTCUSDT");
        order.setExchangeOrderId(202L);
        order.setStrategyId(3L);
        order.setStrategyInstanceId(4L);
        var attribution = new StrategyPositionProjectionService.VerifiedAttribution(
            StrategyPositionAccounting.PositionSide.LONG, StrategyPositionAccounting.Role.CLOSE);
        when(projections.resolveVerifiedAttribution(1L, order, closeFill)).thenReturn(attribution);
        assertEquals("ATTRIBUTED", service.attribute(closeFill, order, 1L));

        var orderOfCalls = inOrder(rebuilds, projections);
        orderOfCalls.verify(rebuilds).invalidateForAttributedClose(1L, "BTCUSDT");
        orderOfCalls.verify(projections).projectAttributed(1L, order, closeFill, attribution);
        orderOfCalls.verify(rebuilds).completeAttributedClose(1L, "BTCUSDT", 77L);
    }

    @Test
    void missingImmutableIntentPreventsAnyStrategyAllocation() {
        when(journal.selectByKey(1L, "BTCUSDT", 101L)).thenReturn(null);
        when(journal.insertRaw(any())).thenReturn(1);
        when(journal.updateStatus(anyLong(), anyString(), any())).thenReturn(1);
        var order = new QuantOrder();
        order.setCredentialId(1L);
        order.setSymbol("BTCUSDT");
        order.setExchangeOrderId(202L);
        order.setStrategyId(3L);
        order.setStrategyInstanceId(4L);
        doThrow(new IllegalStateException("No immutable strategy execution intent")).when(projections).resolveVerifiedAttribution(1L, order, fill());

        assertEquals("PENDING_FACTS", service.attribute(fill(), order, 1L));

        verifyNoInteractions(allocations);
        verify(trades, never()).backfillStrategyAttribution(any(), anyString(), any(), any(), any(), any());
        verify(projections, never()).projectAttributed(anyLong(), any(), any(), any());
    }

    @Test
    void immutableIntentConflictIsQuarantinedInsteadOfRetriedAsMissingFacts() {
        when(journal.selectByKey(1L, "BTCUSDT", 101L)).thenReturn(null);
        when(journal.insertRaw(any())).thenReturn(1);
        when(journal.updateStatus(anyLong(), anyString(), any())).thenReturn(1);
        var order = new QuantOrder();
        order.setCredentialId(1L);
        order.setSymbol("BTCUSDT");
        order.setExchangeOrderId(202L);
        order.setStrategyId(3L);
        order.setStrategyInstanceId(4L);
        doThrow(new IllegalStateException("策略订单意图与成交新事实冲突")).when(projections).resolveVerifiedAttribution(1L, order, fill());

        assertEquals("CONFLICT", service.attribute(fill(), order, 1L));
        verifyNoInteractions(allocations);
        verify(journal).updateStatus(anyLong(), eq("CONFLICT"), contains("新事实冲突"));
    }

    @Test
    void projectionFailureKeepsFillPendingAfterTradeAttributionBackfill() {
        when(journal.selectByKey(1L, "BTCUSDT", 101L)).thenReturn(null);
        when(journal.insertRaw(any())).thenReturn(1);
        when(journal.updateStatus(anyLong(), anyString(), any())).thenReturn(1);
        when(trades.backfillStrategyAttribution(1L, "BTCUSDT", 101L, 202L, 3L, 4L)).thenReturn(1);
        doThrow(new IllegalStateException("No immutable strategy execution intent")).when(projections).projectAttributed(eq(1L), any(), any(), any());
        var order = new QuantOrder();
        order.setCredentialId(1L);
        order.setSymbol("BTCUSDT");
        order.setExchangeOrderId(202L);
        order.setStrategyId(3L);
        order.setStrategyInstanceId(4L);
        when(projections.resolveVerifiedAttribution(1L, order, fill())).thenReturn(
            new StrategyPositionProjectionService.VerifiedAttribution(
                StrategyPositionAccounting.PositionSide.LONG, StrategyPositionAccounting.Role.CLOSE));

        assertEquals("PENDING_FACTS", service.attribute(fill(), order, 1L));

        verify(rebuilds).invalidateForAttributedClose(1L, "BTCUSDT");
        verify(rebuilds, never()).completeAttributedClose(anyLong(), anyString(), anyLong());
        verify(projections).projectAttributed(eq(1L), eq(order), eq(fill()), any(StrategyPositionProjectionService.VerifiedAttribution.class));
        verify(journal).updateStatus(anyLong(), eq("PENDING_FACTS"), contains("immutable"));
        verify(journal, never()).updateStatus(anyLong(), eq("ATTRIBUTED"), isNull());
    }

    @Test
    void tradeAttributionBackfillFillsMissingColumnsWithoutOverwritingConflictingValues() throws Exception {
        var method = org.dromara.quant.account.mapper.QuantTradeMapper.class.getMethod("backfillStrategyAttribution",
            Long.class, String.class, Long.class, Long.class, Long.class, Long.class);
        String sql = method.getAnnotation(org.apache.ibatis.annotations.Update.class).value()[0];
        assertTrue(sql.contains("COALESCE(strategy_id"));
        assertTrue(sql.contains("COALESCE(strategy_instance_id"));
        assertTrue(sql.contains("(strategy_id IS NULL OR strategy_id = #{strategyId})"));
        assertTrue(sql.contains("(strategy_instance_id IS NULL OR strategy_instance_id = #{strategyInstanceId})"));
    }

    @Test
    void fillJournalSqlPreventsStaleWorkersFromRegressingAttributedOrConflictStates() throws Exception {
        try (var stream = getClass().getClassLoader().getResourceAsStream("mapper/quant/StrategyFillJournalMapper.xml")) {
            org.junit.jupiter.api.Assertions.assertNotNull(stream, "fill journal mapper XML must be available");
            String xml = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            String start = "<update id=" + "\"updateStatus\"";
            int from = xml.indexOf(start);
            int to = xml.indexOf("</update>", from);
            org.junit.jupiter.api.Assertions.assertTrue(from >= 0 && to > from, "updateStatus SQL must exist");
            String sql = xml.substring(from, to);
            org.junit.jupiter.api.Assertions.assertTrue(sql.contains("#{status}='CONFLICT'"), sql);
            org.junit.jupiter.api.Assertions.assertTrue(sql.contains("attribution_status <> 'CONFLICT'"), sql);
            org.junit.jupiter.api.Assertions.assertTrue(sql.contains("#{status}='ATTRIBUTED' OR attribution_status <> 'ATTRIBUTED'"), sql);
            int pendingFrom = xml.indexOf("selectPending");
            int pendingTo = xml.indexOf("</select>", pendingFrom);
            org.junit.jupiter.api.Assertions.assertTrue(pendingFrom >= 0 && pendingTo > pendingFrom, "selectPending SQL must exist");
            org.junit.jupiter.api.Assertions.assertTrue(xml.substring(pendingFrom, pendingTo).contains("'PENDING_FACTS'"), "recoverable incomplete facts must be retried");
        }
    }
}
