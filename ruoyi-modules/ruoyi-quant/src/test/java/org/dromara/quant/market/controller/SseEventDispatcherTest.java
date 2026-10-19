package org.dromara.quant.market.controller;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
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
            BlockingQueue<?> queue = (BlockingQueue<?>) ReflectionTestUtils.getField(dispatcher, "queue");
            assertTrue(queue != null && queue.size() == 1);
            Object pending = queue.peek();
            assertTrue(pending != null && "latest".equals(ReflectionTestUtils.getField(pending, "data")));
        } finally {
            releaseFirstWrite.countDown();
            dispatcher.close();
            responseReady.close();
        }
    }
}
