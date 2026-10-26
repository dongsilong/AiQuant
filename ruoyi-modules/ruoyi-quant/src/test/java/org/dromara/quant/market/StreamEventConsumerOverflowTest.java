package org.dromara.quant.market;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamEventConsumerOverflowTest {

    @Test
    void coalescesRepeatedOverflowNotificationsUntilQueueIsReset() throws Exception {
        CountDownLatch firstEventStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstEvent = new CountDownLatch(1);
        AtomicInteger overflowNotifications = new AtomicInteger();
        StreamEventConsumer<String> consumer = new StreamEventConsumer<>(1, event -> {
            if ("first".equals(event)) {
                firstEventStarted.countDown();
                try {
                    releaseFirstEvent.await();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        }, error -> {
            throw new AssertionError("Unexpected consumer failure", error);
        }, overflowNotifications::incrementAndGet);

        try {
            consumer.accept("first");
            assertTrue(firstEventStarted.await(2, TimeUnit.SECONDS));
            consumer.accept("queued-before-overflow");
            consumer.accept("overflow-one");
            assertEquals(1, overflowNotifications.get());

            // The worker is still blocked and the queue is full again. The first overflow
            // already scheduled recovery, so this arrival is dropped without clearing the
            // queued item or scheduling a second recovery.
            consumer.accept("queued-after-overflow");
            consumer.accept("overflow-two");
            assertEquals(1, overflowNotifications.get());
            assertTrue(consumer.statistics().contains("dropped=4"));
            assertTrue(consumer.statistics().contains("queued=0"));
        } finally {
            releaseFirstEvent.countDown();
            consumer.close();
        }
    }
}
