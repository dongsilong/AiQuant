package org.dromara.quant.exchange.binance;

import org.dromara.quant.exchange.ExchangeMarketPort;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RestRequestSchedulerTest {
    @Test
    void manualBlockReportsReasonAndDoesNotSuggestAutomaticRetry() {
        var scheduler = new RestRequestScheduler(1);
        scheduler.blockUntilManualReset();

        var failure = assertThrows(ExchangeMarketPort.RequestDeferredException.class,
            () -> scheduler.acquire(RestRequestScheduler.Kind.POSITION_VERIFY));

        assertEquals("MANUAL_BLOCK", failure.reason());
        assertEquals("POSITION_VERIFY", failure.kind());
        assertEquals(0L, failure.retryAfterMillis());
        assertEquals(5, failure.queueSizes().length);
    }

    @Test
    void cooldownReportsRemainingDelay() {
        var scheduler = new RestRequestScheduler(1);
        scheduler.deferFor(2_000L);

        var failure = assertThrows(ExchangeMarketPort.RequestDeferredException.class,
            () -> scheduler.acquire(RestRequestScheduler.Kind.ACCOUNT));

        assertEquals("BLOCKED_UNTIL", failure.reason());
        assertEquals("ACCOUNT", failure.kind());
        assertTrue(failure.retryAfterMillis() > 0L);
        assertEquals(0, failure.queueSize());
    }

    @Test
    void rejectsRequestWeightOutsideTheSupportedBurstCapacity() {
        var scheduler = new RestRequestScheduler(1);

        assertThrows(IllegalArgumentException.class,
            () -> scheduler.acquire(RestRequestScheduler.Kind.BACKGROUND, 41));
    }

    @Test
    void agedAccountRequestReservesItsWeightFromBackgroundReads() throws InterruptedException {
        var scheduler = new RestRequestScheduler(50);
        scheduler.acquire(RestRequestScheduler.Kind.TRADE, 40);

        var sequence = new java.util.concurrent.atomic.AtomicInteger();
        var accountOrder = new java.util.concurrent.atomic.AtomicInteger();
        var backgroundOrder = new java.util.concurrent.atomic.AtomicInteger();
        var requestFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var accountDone = new java.util.concurrent.CountDownLatch(1);
        var backgroundDone = new java.util.concurrent.CountDownLatch(1);
        Thread account = new Thread(() -> {
            try {
                scheduler.acquire(RestRequestScheduler.Kind.ACCOUNT, 20);
                accountOrder.set(sequence.incrementAndGet());
            } catch (Throwable ex) {
                requestFailure.compareAndSet(null, ex);
            } finally {
                accountDone.countDown();
            }
        });
        Thread background = new Thread(() -> {
            try {
                scheduler.acquire(RestRequestScheduler.Kind.BACKGROUND, 1);
                backgroundOrder.set(sequence.incrementAndGet());
            } catch (Throwable ex) {
                requestFailure.compareAndSet(null, ex);
            } finally {
                backgroundDone.countDown();
            }
        });
        try {
            account.start();
            Thread.sleep(300L);
            background.start();

            assertTrue(accountDone.await(2, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(backgroundDone.await(1, java.util.concurrent.TimeUnit.SECONDS));
            assertNull(requestFailure.get());
            assertTrue(accountOrder.get() < backgroundOrder.get(),
                "aged account request must reserve its remaining weight before background reads");
        } finally {
            account.interrupt();
            background.interrupt();
            account.join(1_000L);
            background.join(1_000L);
        }
    }

    @Test
    void queueTimeoutIncludesTheWaitingTicketInQueueSnapshot() {
        var scheduler = new RestRequestScheduler(60_000L);
        scheduler.acquire(RestRequestScheduler.Kind.TRADE, 40);

        var failure = assertThrows(ExchangeMarketPort.RequestDeferredException.class,
            () -> scheduler.acquire(RestRequestScheduler.Kind.TRADE, 1));

        assertEquals("QUEUE_TIMEOUT", failure.reason());
        assertEquals(1, failure.queueSize());
        assertEquals(1, failure.queueSizes()[RestRequestScheduler.Kind.TRADE.ordinal()]);
    }

    @Test
    void queueKindsKeepPositionVerificationAheadOfInformationalReads() {
        assertEquals(0, RestRequestScheduler.Kind.TRADE.ordinal());
        assertEquals(1, RestRequestScheduler.Kind.PROTECTION_VERIFY.ordinal());
        assertEquals(2, RestRequestScheduler.Kind.POSITION_VERIFY.ordinal());
        assertEquals(3, RestRequestScheduler.Kind.ACCOUNT.ordinal());
        assertEquals(4, RestRequestScheduler.Kind.BACKGROUND.ordinal());
    }
}
