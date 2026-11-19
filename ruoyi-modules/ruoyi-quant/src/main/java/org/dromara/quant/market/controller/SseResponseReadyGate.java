package org.dromara.quant.market.controller;

import jakarta.servlet.http.HttpServletRequest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Holds stream deliveries until Spring MVC has initialized the asynchronous SSE response.
 */
final class SseResponseReadyGate implements AutoCloseable {
    private final CountDownLatch ready = new CountDownLatch(1);
    private final AtomicBoolean closed = new AtomicBoolean();

    SseResponseReadyGate(HttpServletRequest request) {
        Thread.ofVirtual().name("quant-sse-response-ready").start(() -> {
            while (!closed.get()) {
                try {
                    if (request.isAsyncStarted()) {
                        // Spring starts servlet async processing just before initializing the emitter handler.
                        // Allow that initialization to finish before releasing queued stream callbacks.
                        TimeUnit.MILLISECONDS.sleep(25);
                        if (!request.isAsyncStarted()) {
                            continue;
                        }
                        ready.countDown();
                        return;
                    }
                    TimeUnit.MILLISECONDS.sleep(10);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (IllegalStateException ex) {
                    close();
                    return;
                }
            }
        });
    }

    boolean awaitReady() {
        try {
            ready.await();
            return !closed.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            ready.countDown();
        }
    }
}
