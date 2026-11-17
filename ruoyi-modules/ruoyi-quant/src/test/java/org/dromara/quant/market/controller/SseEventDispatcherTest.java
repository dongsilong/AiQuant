package org.dromara.quant.market.controller;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class SseEventDispatcherTest {
    @Test
    void disconnectedClientDuringFailureCleanupDoesNotEscapeDispatcher() {
        SseEventDispatcher dispatcher = new SseEventDispatcher(
            new SseEmitter(0L), new SseResponseReadyGate(new MockHttpServletRequest()),
            () -> { throw new IllegalStateException("complete failed", new AsyncRequestNotUsableException("connection closed")); });

        assertDoesNotThrow(dispatcher::notifyFailure);
    }

    @Test
    void slowSseWriteDoesNotBlockEventProducer() throws Exception {
        CountDownLatch writeStarted = new CountDownLatch(1);
        CountDownLatch releaseWrite = new CountDownLatch(1);
        SseEmitter emitter = new SseEmitter(0L) {
            @Override
            public void send(SseEventBuilder builder) throws IOException {
                writeStarted.countDown();
                try {
                    releaseWrite.await(1, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IOException(ex);
                }
            }
        };

        MockHttpServletRequest request = new MockHttpServletRequest();
        SseResponseReadyGate responseReady = new SseResponseReadyGate(request);
        request.setAsyncStarted(true);
        SseEventDispatcher dispatcher = new SseEventDispatcher(emitter, responseReady, () -> { });
        dispatcher.start();
        try {
            assertTrue(dispatcher.offer("quant.market", "first"));
            assertTrue(writeStarted.await(1, TimeUnit.SECONDS));
            long startedAt = System.nanoTime();
            assertTrue(dispatcher.offer("quant.market", "second"));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) < 100);
        } finally {
            releaseWrite.countDown();
            dispatcher.close();
            responseReady.close();
        }
    }

    @Test
    void queueOverflowTriggersFailureWithoutBlockingEventProducer() throws Exception {
        CountDownLatch writeStarted = new CountDownLatch(1);
        CountDownLatch releaseWrite = new CountDownLatch(1);
        CountDownLatch failureCalled = new CountDownLatch(1);
        SseEmitter emitter = new SseEmitter(0L) {
            @Override
            public void send(SseEventBuilder builder) throws IOException {
                writeStarted.countDown();
                try {
                    releaseWrite.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IOException(ex);
                }
            }
        };
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAsyncStarted(true);
        SseResponseReadyGate responseReady = new SseResponseReadyGate(request);
        SseEventDispatcher dispatcher = new SseEventDispatcher(emitter, responseReady, failureCalled::countDown);
        dispatcher.start();
        try {
            assertTrue(dispatcher.offer("quant.market", "in-flight"));
            assertTrue(writeStarted.await(1, TimeUnit.SECONDS));
            for (int index = 0; index < 256; index++) {
                assertTrue(dispatcher.offer("quant.market", "queued-" + index));
            }
            long startedAt = System.nanoTime();
            assertFalse(dispatcher.offer("quant.market", "overflow"));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            assertTrue(elapsedMs < 100, "queue overflow must not block the event producer");
            assertTrue(failureCalled.await(1, TimeUnit.SECONDS), "queue overflow must trigger connection cleanup");
        } finally {
            releaseWrite.countDown();
            dispatcher.close();
            responseReady.close();
        }
    }

    @Test
    void coalescesUnsentUpdatesForTheSameKey() throws Exception {
        CountDownLatch firstWriteStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstWrite = new CountDownLatch(1);
        SseEmitter emitter = new SseEmitter(0L) {
            private int writes;

            @Override
            public synchronized void send(SseEventBuilder builder) throws IOException {
                writes++;
                if (writes == 1) {
                    firstWriteStarted.countDown();
                    try {
                        releaseFirstWrite.await(1, TimeUnit.SECONDS);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IOException(ex);
                    }
                }
            }
        };
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAsyncStarted(true);
        SseResponseReadyGate responseReady = new SseResponseReadyGate(request);
        SseEventDispatcher dispatcher = new SseEventDispatcher(emitter, responseReady, () -> { });
        dispatcher.start();
        try {
            dispatcher.offer("quant.account", "in-flight");
            assertTrue(firstWriteStarted.await(1, TimeUnit.SECONDS));
            assertTrue(dispatcher.offerLatest("peak", "quant.account", "old"));
            assertTrue(dispatcher.offerLatest("peak", "quant.account", "latest"));
            Map<?, ?> latestStateQueue = (Map<?, ?>) ReflectionTestUtils.getField(dispatcher, "latestStateQueue");
            assertTrue(latestStateQueue != null && latestStateQueue.size() == 1);
            Object pending = latestStateQueue.values().iterator().next();
            assertTrue("latest".equals(ReflectionTestUtils.getField(pending, "data")));
            java.util.concurrent.atomic.AtomicLong coalescedCount =
                (java.util.concurrent.atomic.AtomicLong) ReflectionTestUtils.getField(dispatcher, "coalescedCount");
            assertTrue(coalescedCount != null && coalescedCount.get() == 1);
        } finally {
            releaseFirstWrite.countDown();
            dispatcher.close();
            responseReady.close();
        }
    }
    @Test
    void reliableEventsDoNotStarveLatestStateEvents() {
        SseEventDispatcher dispatcher = new SseEventDispatcher(new SseEmitter(0L),
            new SseResponseReadyGate(new MockHttpServletRequest()), () -> { });
        for (int index = 0; index < 9; index++) {
            assertTrue(dispatcher.offer("quant.account", "reliable-" + index));
        }
        assertTrue(dispatcher.offerLatest("position", "quant.account", "latest-state"));

        for (int index = 0; index < 8; index++) {
            Object pending = ReflectionTestUtils.invokeMethod(dispatcher, "takeNext");
            assertTrue(("reliable-" + index).equals(ReflectionTestUtils.getField(pending, "data")));
        }
        Object state = ReflectionTestUtils.invokeMethod(dispatcher, "takeNext");
        assertTrue("latest-state".equals(ReflectionTestUtils.getField(state, "data")));
        Object remaining = ReflectionTestUtils.invokeMethod(dispatcher, "takeNext");
        assertTrue("reliable-8".equals(ReflectionTestUtils.getField(remaining, "data")));
        dispatcher.close();
    }

    @Test
    void latestStateQueueIsBoundedAndOverflowDisconnectsClient() throws Exception {
        CountDownLatch failureCalled = new CountDownLatch(1);
        SseEventDispatcher dispatcher = new SseEventDispatcher(new SseEmitter(0L),
            new SseResponseReadyGate(new MockHttpServletRequest()), failureCalled::countDown);
        try {
            for (int index = 0; index < 256; index++) {
                assertTrue(dispatcher.offerLatest("state-" + index, "quant.market", index));
            }
            assertFalse(dispatcher.offerLatest("overflow", "quant.market", "overflow"));
            assertTrue(failureCalled.await(1, TimeUnit.SECONDS));
        } finally {
            dispatcher.close();
        }
    }

}
