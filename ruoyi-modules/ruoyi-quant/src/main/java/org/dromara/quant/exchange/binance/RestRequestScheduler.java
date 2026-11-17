package org.dromara.quant.exchange.binance;

import org.dromara.quant.exchange.ExchangeMarketPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;

/**
 * 基于请求权重的全局令牌桶，并按请求类别加权轮转，避免高权重查询挤占全部请求容量。
 */
final class RestRequestScheduler {
    private static final Logger log = LoggerFactory.getLogger(RestRequestScheduler.class);
    private static final double TOKEN_CAPACITY = 40D;
    private static final long RESERVATION_WAIT_NANOS = TimeUnit.MILLISECONDS.toNanos(250L);
    private final ArrayDeque<Ticket>[] queues;
    private final int[] rotation = {0, 1, 0, 2, 0, 0, 1, 3, 0, 4};
    /** 每个权重单位补充所需时间；生产环境 50ms 即每秒最多补充 20 个权重单位。 */
    private final long refillInterval;
    private double availableTokens = TOKEN_CAPACITY;
    private long lastRefill = System.nanoTime();
    private long blockedUntil;
    /** A 418 without a trustworthy expiry requires operator intervention; never auto-expire this latch. */
    private boolean manualBlock;
    private int cursor;

    @SuppressWarnings("unchecked")
    RestRequestScheduler(long refillIntervalMillis) {
        refillInterval = TimeUnit.MILLISECONDS.toNanos(refillIntervalMillis);
        queues = new ArrayDeque[Kind.values().length];
        for (int i = 0; i < queues.length; i++) {
            queues[i] = new ArrayDeque<>();
        }
    }

    synchronized void acquire(Kind kind) {
        acquire(kind, 1);
    }

    synchronized void acquire(Kind kind, int requestWeight) {
        if (requestWeight < 1 || requestWeight > TOKEN_CAPACITY) {
            throw new IllegalArgumentException("Binance REST 请求权重超出调度器支持范围: " + requestWeight);
        }
        long initialNow = System.nanoTime();
        if (manualBlock) {
            throw deferred("MANUAL_BLOCK", kind, 0L, 0L);
        }
        if (initialNow < blockedUntil) {
            throw deferred("BLOCKED_UNTIL", kind, 0L, TimeUnit.NANOSECONDS.toMillis(blockedUntil - initialNow) + 1L);
        }
        var queue = queues[kind.ordinal()];
        if (queue.size() >= 16) {
            throw deferred("QUEUE_FULL", kind, 0L, 2_000L);
        }
        Ticket ticket = new Ticket(kind, requestWeight, initialNow);
        queue.addLast(ticket);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(kind == Kind.TRADE ? 2 : 8);
        try {
            for (;;) {
                if (Thread.currentThread().isInterrupted()) {
                    throw deferred("INTERRUPTED", kind, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - initialNow), 0L);
                }
                if (manualBlock) {
                    throw deferred("MANUAL_BLOCK", kind, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - initialNow), 0L);
                }
                long now = System.nanoTime();
                if (now < blockedUntil) {
                    throw deferred("BLOCKED_UNTIL", kind, TimeUnit.NANOSECONDS.toMillis(now - initialNow), TimeUnit.NANOSECONDS.toMillis(blockedUntil - now) + 1L);
                }
                if (now >= deadline) {
                    if (!queue.contains(ticket)) {
                        log.error("Binance REST 调度器队列状态异常 reason=QUEUE_TIMEOUT kind={} requestWeight={} waitedMs={} queueSizes={}",
                            kind, requestWeight, TimeUnit.NANOSECONDS.toMillis(now - initialNow), java.util.Arrays.toString(queueSizes()));
                    }
                    throw deferred("QUEUE_TIMEOUT", kind, TimeUnit.NANOSECONDS.toMillis(now - initialNow), 1_500L);
                }
                refillTokens(now);
                int selected = selectEligibleQueue(now);
                if (selected >= 0 && rotation[selected] == kind.ordinal() && queue.peekFirst() == ticket) {
                    queue.removeFirst();
                    availableTokens -= requestWeight;
                    long waitedMillis = TimeUnit.NANOSECONDS.toMillis(now - initialNow);
                    cursor = (selected + 1) % rotation.length;
                    notifyAll();
                    return;
                }
                long tokenDelay = nextEligibleTokenDelayNanos(now);
                long fairnessDelay = TimeUnit.MILLISECONDS.toNanos(10);
                long delay = tokenDelay == Long.MAX_VALUE ? fairnessDelay : Math.min(tokenDelay, fairnessDelay);
                TimeUnit.NANOSECONDS.timedWait(this, Math.min(delay, deadline - now));
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw deferred("INTERRUPTED", kind, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - initialNow), 0L);
        } finally {
            queue.remove(ticket);
            notifyAll();
        }
    }

    private void refillTokens(long now) {
        long elapsed = now - lastRefill;
        if (elapsed <= 0L) {
            return;
        }
        availableTokens = Math.min(TOKEN_CAPACITY, availableTokens + (double) elapsed / refillInterval);
        lastRefill = now;
    }

    private int selectEligibleQueue(long now) {
        Ticket reservation = reservationTicket(now);
        for (int i = 0; i < rotation.length; i++) {
            int selected = (cursor + i) % rotation.length;
            Kind kind = Kind.values()[rotation[selected]];
            Ticket head = queues[kind.ordinal()].peekFirst();
            if (head == null || head.weight() > availableTokens) {
                continue;
            }
            if (reservation != null && head != reservation
                && priority(kind) >= priority(reservation.kind())
                && availableTokens - head.weight() < reservation.weight()) {
                continue;
            }
            return selected;
        }
        return -1;
    }

    private long nextEligibleTokenDelayNanos(long now) {
        Ticket reservation = reservationTicket(now);
        int minimumWeight = Integer.MAX_VALUE;
        for (Kind kind : Kind.values()) {
            Ticket head = queues[kind.ordinal()].peekFirst();
            if (head == null) {
                continue;
            }
            if (reservation != null && head != reservation
                && priority(kind) >= priority(reservation.kind())
                && availableTokens - head.weight() < reservation.weight()) {
                continue;
            }
            minimumWeight = Math.min(minimumWeight, head.weight());
        }
        if (minimumWeight == Integer.MAX_VALUE || minimumWeight <= availableTokens) {
            return TimeUnit.MILLISECONDS.toNanos(10);
        }
        return Math.max(1L, (long) Math.ceil((minimumWeight - availableTokens) * refillInterval));
    }

    private Ticket reservationTicket(long now) {
        Ticket reservation = null;
        for (Kind kind : Kind.values()) {
            Ticket head = queues[kind.ordinal()].peekFirst();
            if (head == null) {
                continue;
            }
            boolean critical = priority(kind) <= priority(Kind.POSITION_VERIFY);
            if (!critical && now - head.enqueuedAtNanos() < RESERVATION_WAIT_NANOS) {
                continue;
            }
            if (reservation == null || priority(kind) < priority(reservation.kind())
                || priority(kind) == priority(reservation.kind())
                    && head.enqueuedAtNanos() < reservation.enqueuedAtNanos()) {
                reservation = head;
            }
        }
        return reservation;
    }

    private static int priority(Kind kind) {
        return switch (kind) {
            case PROTECTION_VERIFY -> 0;
            case TRADE, POSITION_VERIFY -> 1;
            case ACCOUNT -> 2;
            case BACKGROUND -> 3;
        };
    }

    private int[] queueSizes() {
        int[] sizes = new int[queues.length];
        for (int i = 0; i < queues.length; i++) {
            sizes[i] = queues[i].size();
        }
        return sizes;
    }

    private ExchangeMarketPort.RequestDeferredException deferred(String reason, Kind kind, long waitedMillis, long retryAfterMillis) {
        int[] sizes = queueSizes();
        return new ExchangeMarketPort.RequestDeferredException(reason, kind.name(), sizes[kind.ordinal()],
            sizes, waitedMillis, retryAfterMillis);
    }

    /**
     * Permanently blocks REST requests for this process after an unbounded HTTP 418.
     * Restarting the process is an explicit operator action, not an automatic expiry.
     */
    synchronized void blockUntilManualReset() {
        manualBlock = true;
        notifyAll();
    }

    synchronized boolean isManuallyBlocked() {
        return manualBlock;
    }

    synchronized void deferFor(long delayMillis) {
        long duration = TimeUnit.MILLISECONDS.toNanos(Math.max(1L, delayMillis));
        blockedUntil = Math.max(blockedUntil, System.nanoTime() + duration);
        notifyAll();
    }

    private record Ticket(Kind kind, int weight, long enqueuedAtNanos) { }

    /**
     * 令牌桶按每秒 20 个 IP 权重单位补充、最多积累 40 个单位；类别份额仍由加权轮转保证。
     */
    enum Kind {TRADE, PROTECTION_VERIFY, POSITION_VERIFY, ACCOUNT, BACKGROUND}
}
