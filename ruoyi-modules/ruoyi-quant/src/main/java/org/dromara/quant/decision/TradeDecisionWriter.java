package org.dromara.quant.decision;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/** 有界非阻塞投递；数据库、重试、告警均只在日志线程执行。 */
public final class TradeDecisionWriter implements AutoCloseable {
    private final LinkedBlockingQueue<DecisionLogEvent> queue;
    private final Consumer<List<DecisionLogEvent>> sink;
    private final LongConsumer alarm;
    private final AtomicLong dropped = new AtomicLong();
    private volatile boolean closing;
    private Thread worker;
    private long nextAlarmAt;
    private long announced;

    public TradeDecisionWriter(int capacity, Consumer<List<DecisionLogEvent>> sink, LongConsumer alarm) {
        this.queue = new LinkedBlockingQueue<>(capacity);
        this.sink = sink;
        this.alarm = alarm;
    }

    public boolean offer(DecisionLogEvent event) {
        if (closing || !queue.offer(event)) {
            dropped.incrementAndGet();
            return false;
        }
        if (closing && queue.remove(event)) {
            dropped.incrementAndGet();
            return false;
        }
        return true;
    }

    public long dropped() { return dropped.get(); }

    public void start() {
        worker = Thread.ofPlatform().daemon().name("quant-trade-decision-log").start(this::run);
    }

    private void run() {
        while (!closing || !queue.isEmpty()) {
            List<DecisionLogEvent> batch = new ArrayList<>(200);
            try {
                DecisionLogEvent first = queue.poll(500, TimeUnit.MILLISECONDS);
                if (first != null) {
                    batch.add(first);
                    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
                    while (batch.size() < 200 && !closing) {
                        long remaining = deadline - System.nanoTime();
                        if (remaining <= 0) { break; }
                        DecisionLogEvent next = queue.poll(remaining, TimeUnit.NANOSECONDS);
                        if (next == null) { break; }
                        batch.add(next);
                    }
                    persist(batch);
                }
                announceLoss();
            } catch (InterruptedException ex) {
                dropped.addAndGet(queue.size() + batch.size());
                queue.clear();
                announceLoss();
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void persist(List<DecisionLogEvent> batch) throws InterruptedException {
        long[] delay = {1000, 2000, 5000};
        for (int attempt = 0; ; attempt++) {
            try {
                sink.accept(batch);
                return;
            } catch (RuntimeException ex) {
                if (attempt == delay.length || closing) {
                    dropped.addAndGet(batch.size());
                    return;
                }
                try {
                    Thread.sleep(delay[attempt]);
                } catch (InterruptedException interrupted) {
                    throw interrupted;
                }
            }
        }
    }

    private void announceLoss() {
        long count = dropped.get();
        long now = System.currentTimeMillis();
        if (count > announced && now >= nextAlarmAt) {
            nextAlarmAt = now + 60_000;
            try {
                alarm.accept(count);
                announced = count;
            } catch (RuntimeException ignored) {
                // 告警故障不能终止写入线程。
            }
        }
    }

    @Override
    public void close() {
        closing = true;
        if (worker == null) { return; }
        try {
            worker.join(5000);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        if (worker.isAlive()) { worker.interrupt(); }
    }
}
