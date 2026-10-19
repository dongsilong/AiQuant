package org.dromara.quant.market.controller;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** 将异步响应写入移出事件生产线程；慢客户端满队列后断开并通过重连恢复状态。 */
@Slf4j
final class SseEventDispatcher implements AutoCloseable {
    private record Pending(String eventName, Object data, String coalesceKey, long queuedAtNanos) { }

    private final SseEmitter emitter;
    private final SseResponseReadyGate responseReady;
    private final Runnable failure;
    private final ArrayBlockingQueue<Pending> queue = new ArrayBlockingQueue<>(256);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong nextDelayWarningAt = new AtomicLong();
    private Thread worker;

    SseEventDispatcher(SseEmitter emitter, SseResponseReadyGate responseReady, Runnable failure) {
        this.emitter = emitter;
        this.responseReady = responseReady;
        this.failure = failure;
    }

    synchronized void start() {
        if (worker != null || closed.get()) return;
        worker = Thread.ofVirtual().name("quant-sse-writer").start(this::writeLoop);
    }

    synchronized boolean offer(String eventName, Object data) {
        return enqueue(new Pending(eventName, data, null, System.nanoTime()));
    }

    /** Replace an unsent event with the newest state for the same key. */
    synchronized boolean offerLatest(String coalesceKey, String eventName, Object data) {
        if (closed.get()) return false;
        queue.removeIf(pending -> coalesceKey.equals(pending.coalesceKey()));
        return enqueue(new Pending(eventName, data, coalesceKey, System.nanoTime()));
    }

    private boolean enqueue(Pending pending) {
        if (closed.get()) return false;
        if (queue.offer(pending)) return true;
        notifyFailure();
        return false;
    }

    private void writeLoop() {
        while (!closed.get()) {
            try {
                Pending pending = queue.take();
                long queueDelayMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - pending.queuedAtNanos());
                long now = System.currentTimeMillis();
                long nextWarningAt = nextDelayWarningAt.get();
                if (queueDelayMillis >= 1_000 && now >= nextWarningAt && nextDelayWarningAt.compareAndSet(nextWarningAt, now + 10_000L)) {
                    log.warn("SSE 下游发送排队延迟 eventName={} queueDelayMs={} queueSize={}", pending.eventName(), queueDelayMillis, queue.size());
                }
                if (!responseReady.awaitReady()) return;
                emitter.send(SseEmitter.event().name(pending.eventName()).data(pending.data()));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException | RuntimeException ex) {
                notifyFailure();
                return;
            }
        }
    }

    void notifyFailure() {
        try {
            failure.run();
        } catch (Exception ex) {
            if (hasCause(ex, AsyncRequestNotUsableException.class)) {
                log.debug("SSE 客户端已断开，跳过响应完成");
                return;
            }
            throw ex instanceof RuntimeException runtimeException ? runtimeException : new IllegalStateException(ex);
        }
    }

    private static boolean hasCause(Throwable failure, Class<? extends Throwable> causeType) {
        for (Throwable cause = failure; cause != null && cause != cause.getCause(); cause = cause.getCause()) {
            if (causeType.isInstance(cause)) return true;
        }
        return false;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            queue.clear();
            Thread current = worker;
            if (current != null) current.interrupt();
        }
    }
}
