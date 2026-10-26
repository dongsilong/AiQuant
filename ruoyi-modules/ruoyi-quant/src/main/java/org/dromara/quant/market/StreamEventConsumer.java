package org.dromara.quant.market;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/**
 * 每个订阅独立串行消费；满队列明确触发恢复，不阻塞网络读取线程。
 */
final class StreamEventConsumer<T> implements AutoCloseable {
    private final ArrayBlockingQueue<T> queue;
    private final AtomicBoolean closed = new AtomicBoolean();
    /** One queue-overflow notification per recovery cycle, even if producers remain faster than the consumer. */
    private final AtomicBoolean overflowPending = new AtomicBoolean();
    private final Thread worker;
    private final Runnable overflow;
    private final LongAdder received = new LongAdder();
    private final LongAdder processed = new LongAdder();
    private final LongAdder failed = new LongAdder();
    private final LongAdder dropped = new LongAdder();

    StreamEventConsumer(int capacity, Consumer<T> listener, Consumer<RuntimeException> failure, Runnable overflow) {
        this.queue = new ArrayBlockingQueue<>(Math.max(1, capacity));
        this.overflow = overflow;
        worker = Thread.ofVirtual().name("quant-stream-consumer").start(() -> {
            while (!closed.get()) {
                try {
                    T event = queue.take();
                    if (!closed.get()) {
                        try {
                            listener.accept(event);
                            processed.increment();
                        } catch (RuntimeException ex) {
                            failed.increment();
                            failure.accept(ex);
                        }
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }

    void accept(T event) {
        if (closed.get()) {
            return;
        }
        received.increment();
        if (overflowPending.get()) {
            // Do not enqueue post-gap events before the recovery callback has established
            // a new committed cursor; otherwise the worker could process a partial sequence.
            dropped.increment();
            return;
        }
        if (!queue.offer(event)) {
            if (overflowPending.compareAndSet(false, true)) {
                // The queued events and this event cannot be trusted as a complete sequence.
                // Clear once and request exact replay from the committed cursor. While that
                // recovery is being scheduled, drop new arrivals without repeatedly clearing
                // the queue and amplifying the backlog.
                dropped.add(queue.size() + 1L);
                queue.clear();
                overflow.run();
            } else {
                dropped.increment();
            }
        }
    }

    /**
     * 回放可对恢复线程施加背压，但不能阻塞WebSocket线程。
     */
    void acceptReplay(T event) {
        if (closed.get()) {
            return;
        }
        received.increment();
        if (overflowPending.get()) {
            dropped.increment();
            throw new IllegalStateException("行情恢复消费者正在恢复，拒绝旧代次回放");
        }
        try {
            if (!queue.offer(event, 3, java.util.concurrent.TimeUnit.SECONDS)) {
                dropped.increment();
                if (overflowPending.compareAndSet(false, true)) {
                    overflow.run();
                }
                throw new IllegalStateException("行情回放消费者持续阻塞");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("行情回放被中断", ex);
        }
    }

    String statistics() {
        return "received=" + received.sum() + ", processed=" + processed.sum() + ", failed=" + failed.sum() + ", dropped=" + dropped.sum() + ", queued=" + queue.size();
    }

    void clear() {
        dropped.add(queue.size());
        queue.clear();
        overflowPending.set(false);
    }

    /**
     * 停止队列消费并释放消费者持有的资源。
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            queue.clear();
            worker.interrupt();
        }
    }
}
