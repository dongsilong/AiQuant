package org.dromara.quant.decision;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.Tag("dev")
class TradeDecisionLogTest {
    @Test
    void confirmedPositionSuppressesOnlyRepeatedEntryEvaluation() {
        var repository = org.mockito.Mockito.mock(TradeDecisionLogRepository.class);
        var service = new TradeDecisionLogService(repository, org.mockito.Mockito.mock(org.dromara.quant.notification.QuantTradeNotificationService.class));
        var blocked = DecisionLogEvent.builder().operation("OPEN").status("BLOCKED").reasonCode("POSITION_OPEN").build();
        service.record(blocked);
        service.record(blocked.toBuilder().reasonCode("RECOVERY_REQUIRED").build());
        service.record(blocked.toBuilder().operation("CLOSE").status("FILLED").build());
        service.start();
        service.close();
        var batches = org.mockito.ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.atLeastOnce()).writeBatch(batches.capture());
        var events = batches.getAllValues().stream().flatMap(List::stream).toList();
        assertEquals(2, events.size());
        assertFalse(events.contains(blocked));
    }

    @Test
    void changesAndNewBarsStartNewSegments() {
        DecisionLogCursor cursor = new DecisionLogCursor();
        Instant bar = Instant.parse("2026-10-11T06:15:00Z");
        String first = cursor.key(bar, "MAX_POSITIONS");
        assertEquals(first, cursor.key(bar, "MAX_POSITIONS"));
        assertNotEquals(first, cursor.key(bar, "BALANCE"));
        String next = cursor.key(bar.plusSeconds(900), "MAX_POSITIONS");
        assertNotEquals(first, next);
        cursor.reset();
        assertNotEquals(next, cursor.key(bar.plusSeconds(900), "MAX_POSITIONS"));
    }

    @Test
    void sensitiveExceptionPayloadNeverLeaksToLog() {
        String reason = TradeDecisionLogService.safeReason(new org.dromara.common.core.exception.ServiceException("Authorization: Bearer EXAMPLE_ONLY; apiKey=EXAMPLE_KEY"));
        assertFalse(reason.contains("EXAMPLE_ONLY"));
        assertFalse(reason.contains("EXAMPLE_KEY"));
    }

    @Test
    void orderSnapshotRetainsConfirmedExecutedQuantityAndTime() {
        var service = org.mockito.Mockito.spy(new TradeDecisionLogService(org.mockito.Mockito.mock(TradeDecisionLogRepository.class),
            org.mockito.Mockito.mock(org.dromara.quant.notification.QuantTradeNotificationService.class)));
        org.mockito.Mockito.doNothing().when(service).record(org.mockito.ArgumentMatchers.any());
        var now = Instant.now();
        service.order(1L, new org.dromara.quant.exchange.ExchangeGateway.OrderSnapshot("BTCUSDT", 88L, "client", "BUY", "MARKET", "FILLED", java.math.BigDecimal.ZERO,
            java.math.BigDecimal.TWO, java.math.BigDecimal.TWO, now));
        var event = org.mockito.ArgumentCaptor.forClass(DecisionLogEvent.class);
        org.mockito.Mockito.verify(service).record(event.capture());
        assertEquals(java.math.BigDecimal.TWO, event.getValue().quantity());
        assertEquals(now, event.getValue().tradeTime());
        assertNull(event.getValue().price(), "market order price zero is not its fill price");
    }

    @Test
    void orderSnapshotAverageAndExecutedQuantityProduceActualNotional() {
        var service = org.mockito.Mockito.spy(new TradeDecisionLogService(org.mockito.Mockito.mock(TradeDecisionLogRepository.class),
            org.mockito.Mockito.mock(org.dromara.quant.notification.QuantTradeNotificationService.class)));
        org.mockito.Mockito.doNothing().when(service).record(org.mockito.ArgumentMatchers.any());
        var now = Instant.now();
        service.order(1L, new org.dromara.quant.exchange.ExchangeGateway.OrderSnapshot("BTCUSDT", 88L, "client", "SELL", "MARKET", "FILLED", java.math.BigDecimal.ZERO,
            java.math.BigDecimal.TWO, java.math.BigDecimal.TWO, now, new java.math.BigDecimal("101.25")));
        var event = org.mockito.ArgumentCaptor.forClass(DecisionLogEvent.class);
        org.mockito.Mockito.verify(service).record(event.capture());
        assertEquals(new java.math.BigDecimal("202.50"), event.getValue().quantity().multiply(event.getValue().price()));
        assertEquals("FILLED", event.getValue().status());
    }

    @Test
    void fullQueueCannotBlockOrInvokeDatabaseOnProducer() {
        TradeDecisionWriter writer = new TradeDecisionWriter(1, batch -> fail("producer must not persist"), count -> {
        });
        DecisionLogEvent event = DecisionLogEvent.builder().eventKey("one").status("BLOCKED").build();
        assertTrue(writer.offer(event));
        assertTimeoutPreemptively(java.time.Duration.ofMillis(200), () -> assertFalse(writer.offer(event)));
        assertEquals(1, writer.dropped());
    }

    @Test
    void databaseFailureNeverEscapesProducerAndShutdownCountsLoss() throws Exception {
        CountDownLatch failed = new CountDownLatch(1);
        CountDownLatch alarm = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<RuntimeException> writeFailure = new java.util.concurrent.atomic.AtomicReference<>();
        TradeDecisionWriter writer = new TradeDecisionWriter(10, batch -> {
            failed.countDown();
            throw new IllegalStateException("database unavailable");
        }, count -> alarm.countDown(), writeFailure::set);
        writer.start();
        assertDoesNotThrow(() -> writer.offer(DecisionLogEvent.builder().eventKey("failure").build()));
        assertTrue(failed.await(2, TimeUnit.SECONDS));
        writer.close();
        assertEquals(1, writer.dropped());
        assertTrue(alarm.await(1, TimeUnit.SECONDS));
        assertEquals("database unavailable", writeFailure.get().getMessage());
    }

    @Test
    void workerWritesOffThreadAndCountsFailure() throws Exception {
        CountDownLatch written = new CountDownLatch(1);
        String producer = Thread.currentThread().getName();
        TradeDecisionWriter writer = new TradeDecisionWriter(10, batch -> {
            assertNotEquals(producer, Thread.currentThread().getName());
            assertEquals("策略名称快照", batch.getFirst().strategyName());
            written.countDown();
        }, count -> {
        });
        writer.start();
        assertTrue(writer.offer(DecisionLogEvent.builder().eventKey("event").strategyName("策略名称快照").build()));
        assertTrue(written.await(2, TimeUnit.SECONDS));
        writer.close();
        assertEquals(0, writer.dropped());
        assertFalse(writer.offer(DecisionLogEvent.builder().eventKey("after-close").build()));
    }
}
