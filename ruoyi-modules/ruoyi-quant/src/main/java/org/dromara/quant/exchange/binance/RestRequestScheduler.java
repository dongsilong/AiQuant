package org.dromara.quant.exchange.binance;

import org.dromara.quant.exchange.ExchangeMarketPort;

import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;

/**
 * 有界轮转队列；交易占两个轮次，账户校准与后台查询各占一个，避免饥饿。
 */
final class RestRequestScheduler {
    private final ArrayDeque<Object>[] queues;
    private final int[] rotation = {0, 1, 0, 2};
    private static final long MANUAL_BLOCK_RETRY_HINT_MILLIS = TimeUnit.HOURS.toMillis(24);
    private final long interval;
    private long nextStart;
    private long blockedUntil;
    /** A 418 without a trustworthy expiry requires operator intervention; never auto-expire this latch. */
    private boolean manualBlock;
    private int cursor;
    @SuppressWarnings("unchecked")
    RestRequestScheduler(long intervalMillis) {
        interval = TimeUnit.MILLISECONDS.toNanos(intervalMillis);
        queues = new ArrayDeque[]{new ArrayDeque<>(), new ArrayDeque<>(), new ArrayDeque<>()};
    }

    synchronized void acquire(Kind kind) {
        if (manualBlock) {
            throw new ExchangeMarketPort.RequestDeferredException(MANUAL_BLOCK_RETRY_HINT_MILLIS);
        }
        long initialNow = System.nanoTime();
        if (initialNow < blockedUntil) {
            throw new ExchangeMarketPort.RequestDeferredException(TimeUnit.NANOSECONDS.toMillis(blockedUntil - initialNow) + 1L);
        }
        var queue = queues[kind.ordinal()];
        if (queue.size() >= 16) {
            throw new ExchangeMarketPort.RequestDeferredException();
        }
        Object ticket = new Object();
        queue.addLast(ticket);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(kind == Kind.TRADE ? 2 : 8);
        try {
            for (; ; ) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new ExchangeMarketPort.RequestDeferredException();
                }
                if (manualBlock) {
                    throw new ExchangeMarketPort.RequestDeferredException(MANUAL_BLOCK_RETRY_HINT_MILLIS);
                }
                long now = System.nanoTime();
                if (now < blockedUntil) {
                    throw new ExchangeMarketPort.RequestDeferredException(TimeUnit.NANOSECONDS.toMillis(blockedUntil - now) + 1L);
                }
                if (now >= deadline) {
                    throw new ExchangeMarketPort.RequestDeferredException();
                }
                int selected = cursor;
                for (int i = 0; i < rotation.length; i++) {
                    selected = (cursor + i) % rotation.length;
                    if (!queues[rotation[selected]].isEmpty()) {
                        break;
                    }
                }
                if (rotation[selected] == kind.ordinal() && queue.peekFirst() == ticket && now >= nextStart) {
                    queue.removeFirst();
                    nextStart = now + interval;
                    cursor = (selected + 1) % rotation.length;
                    notifyAll();
                    return;
                }
                long delay = nextStart > now ? nextStart - now : TimeUnit.MILLISECONDS.toNanos(10);
                TimeUnit.NANOSECONDS.timedWait(this, Math.min(delay, deadline - now));
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ExchangeMarketPort.RequestDeferredException();
        } finally {
            queue.remove(ticket);
            notifyAll();
        }
    }

    /**
     * Permanently blocks REST requests for this process after an unbounded HTTP 418.
     * Restarting the process is an explicit operator action, not an automatic expiry.
     */
    synchronized void blockUntilManualReset() {
        manualBlock = true;
        notifyAll();
    }

    synchronized void deferFor(long delayMillis) {
        long duration = TimeUnit.MILLISECONDS.toNanos(Math.max(1L, delayMillis));
        blockedUntil = Math.max(blockedUntil, System.nanoTime() + duration);
        notifyAll();
    }

    /**
     * REST 请求优先级：交易请求优先于账户和后台补偿查询。
     */
    enum Kind {TRADE, ACCOUNT, BACKGROUND}
}
