package org.dromara.quant.market.controller;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.dromara.quant.exchange.ExchangeGateway;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** 将异步响应写入移出事件生产线程；可靠事件保序，状态事件按键合并，慢客户端满队列后断开。 */
@Slf4j
final class SseEventDispatcher implements AutoCloseable {
    private static final int RELIABLE_QUEUE_CAPACITY = 256;
    private static final int LATEST_STATE_CAPACITY = 256;

    private record Pending(String eventName, Object data, String coalesceKey, String comment, long queuedAtNanos) { }

    private final SseEmitter emitter;
    private final SseResponseReadyGate responseReady;
    private final Runnable failure;
    private final Object queueLock = new Object();
    private final ArrayBlockingQueue<Pending> reliableQueue = new ArrayBlockingQueue<>(RELIABLE_QUEUE_CAPACITY);
    private final LinkedHashMap<String, Pending> latestStateQueue = new LinkedHashMap<>();
    private final Semaphore available = new Semaphore(0);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean failureNotified = new AtomicBoolean();
    private final AtomicLong nextDelayWarningAt = new AtomicLong();
    private final AtomicLong coalescedCount = new AtomicLong();
    private Thread worker;
    private int consecutiveReliableWrites;

    SseEventDispatcher(SseEmitter emitter, SseResponseReadyGate responseReady, Runnable failure) {
        this.emitter = emitter;
        this.responseReady = responseReady;
        this.failure = failure;
    }

    synchronized void start() {
        if (worker != null || closed.get()) return;
        worker = Thread.ofVirtual().name("quant-sse-writer").start(this::writeLoop);
    }

    boolean offer(String eventName, Object data) {
        long startedAt = System.nanoTime();
        boolean accepted;
        int queueSize;
        synchronized (queueLock) {
            accepted = !closed.get() && reliableQueue.offer(new Pending(eventName, data, null, null, System.nanoTime()));
            if (accepted) available.release();
            queueSize = queueSize();
        }
        if (!accepted && !closed.get()) notifyFailureAsync(eventName, queueSize);
        logEnqueueDelay("SSE 事件", eventName, startedAt, queueSize, accepted);
        return accepted;
    }

    /** 保留同一业务键尚未发送的最新状态；不同业务键相互独立。 */
    boolean offerLatest(String coalesceKey, String eventName, Object data) {
        long startedAt = System.nanoTime();
        boolean accepted;
        int queueSize;
        synchronized (queueLock) {
            if (closed.get()) return false;
            Pending replaced = latestStateQueue.remove(coalesceKey);
            if (replaced == null && latestStateQueue.size() >= LATEST_STATE_CAPACITY) {
                accepted = false;
            } else {
                latestStateQueue.put(coalesceKey, new Pending(eventName, data, coalesceKey, null, System.nanoTime()));
                accepted = true;
                if (replaced == null) available.release();
                else coalescedCount.incrementAndGet();
            }
            queueSize = queueSize();
        }
        if (!accepted && !closed.get()) notifyFailureAsync(eventName, queueSize);
        logEnqueueDelay("SSE 最新状态", eventName, startedAt, queueSize, accepted);
        return accepted;
    }

    /** 心跳使用状态队列，同一连接最多保留一条待发送心跳。 */
    boolean offerComment(String key, String comment) {
        long startedAt = System.nanoTime();
        boolean accepted;
        int queueSize;
        synchronized (queueLock) {
            if (closed.get()) return false;
            Pending replaced = latestStateQueue.remove(key);
            if (replaced == null && latestStateQueue.size() >= LATEST_STATE_CAPACITY) {
                accepted = false;
            } else {
                latestStateQueue.put(key, new Pending(null, null, key, comment, System.nanoTime()));
                accepted = true;
                if (replaced == null) available.release();
                else coalescedCount.incrementAndGet();
            }
            queueSize = queueSize();
        }
        if (!accepted && !closed.get()) notifyFailureAsync("comment", queueSize);
        logEnqueueDelay("SSE 心跳", "comment", startedAt, queueSize, accepted);
        return accepted;
    }

    private int queueSize() {
        return reliableQueue.size() + latestStateQueue.size();
    }

    private void logEnqueueDelay(String label, String eventName, long startedAt, int queueSize, boolean accepted) {
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        if (elapsedMs >= 100) {
            log.warn("{}入队耗时异常 eventName={} elapsedMs={} queueSize={} accepted={} thread={}",
                label, eventName, elapsedMs, queueSize, accepted, Thread.currentThread().getName());
        }
    }

    private void notifyFailureAsync(String eventName, int queueSize) {
        if (failureNotified.compareAndSet(false, true)) {
            log.warn("SSE发送队列已满，关闭慢客户端连接 eventName={} queueSize={} reliableCapacity={} latestStateCapacity={}",
                eventName, queueSize, RELIABLE_QUEUE_CAPACITY, LATEST_STATE_CAPACITY);
            Thread.ofVirtual().name("quant-sse-failure").start(this::notifyFailure);
        }
    }

    private Pending takeNext() throws InterruptedException {
        available.acquire();
        synchronized (queueLock) {
            boolean preferLatestState = consecutiveReliableWrites >= 8 && !latestStateQueue.isEmpty();
            if (!preferLatestState) {
                Pending reliable = reliableQueue.poll();
                if (reliable != null) {
                    consecutiveReliableWrites++;
                    return reliable;
                }
            }
            Iterator<Map.Entry<String, Pending>> iterator = latestStateQueue.entrySet().iterator();
            if (iterator.hasNext()) {
                Pending latest = iterator.next().getValue();
                iterator.remove();
                consecutiveReliableWrites = 0;
                return latest;
            }
            Pending reliable = reliableQueue.poll();
            if (reliable != null) {
                consecutiveReliableWrites++;
                return reliable;
            }
            return null;
        }
    }

    private void writeLoop() {
        while (!closed.get()) {
            try {
                Pending pending = takeNext();
                if (pending == null) continue;
                long queueDelayMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - pending.queuedAtNanos());
                int reliableSize;
                int latestSize;
                long oldestEventAgeMs;
                synchronized (queueLock) {
                    reliableSize = reliableQueue.size();
                    latestSize = latestStateQueue.size();
                    oldestEventAgeMs = oldestEventAgeMillis();
                }
                long now = System.currentTimeMillis();
                long nextWarningAt = nextDelayWarningAt.get();
                if (queueDelayMillis >= 1_000 && now >= nextWarningAt && nextDelayWarningAt.compareAndSet(nextWarningAt, now + 10_000L)) {
                    log.warn("SSE 下游发送排队延迟 eventName={} eventType={} queueDelayMs={} reliableQueueSize={} latestStateQueueSize={} queueSize={} oldestEventAgeMs={} coalescedCount={}",
                        pending.eventName(), eventType(pending), queueDelayMillis, reliableSize, latestSize,
                        reliableSize + latestSize, oldestEventAgeMs, coalescedCount.get());
                }
                if (!responseReady.awaitReady()) return;
                long sendStartedAt = System.nanoTime();
                if (pending.comment() != null) {
                    emitter.send(SseEmitter.event().comment(pending.comment()));
                } else {
                    emitter.send(SseEmitter.event().name(pending.eventName()).data(pending.data()));
                }
                long sendDurationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sendStartedAt);
                if (sendDurationMs >= 500) {
                    synchronized (queueLock) {
                        log.warn("SSE 下游写出耗时偏高 eventName={} eventType={} sendDurationMs={} reliableQueueSize={} latestStateQueueSize={}",
                            pending.eventName(), eventType(pending), sendDurationMs, reliableQueue.size(), latestStateQueue.size());
                    }
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException | RuntimeException ex) {
                notifyFailure();
                return;
            }
        }
    }

    private long oldestEventAgeMillis() {
        long oldest = Long.MAX_VALUE;
        Pending reliable = reliableQueue.peek();
        if (reliable != null) oldest = Math.min(oldest, reliable.queuedAtNanos());
        for (Pending pending : latestStateQueue.values()) {
            oldest = Math.min(oldest, pending.queuedAtNanos());
        }
        return oldest == Long.MAX_VALUE ? 0L : TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - oldest);
    }

    private static String eventType(Pending pending) {
        if (pending.comment() != null) return "COMMENT";
        if (pending.data() instanceof Map<?, ?> data) {
            Object type = data.get("type");
            if (type != null) return String.valueOf(type);
        }
        if (pending.data() instanceof ExchangeGateway.MarketEvent event) return event.type();
        return pending.data() == null ? "UNKNOWN" : pending.data().getClass().getSimpleName();
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
            synchronized (queueLock) {
                reliableQueue.clear();
                latestStateQueue.clear();
            }
            available.release();
            Thread current = worker;
            if (current != null) current.interrupt();
        }
    }
}
