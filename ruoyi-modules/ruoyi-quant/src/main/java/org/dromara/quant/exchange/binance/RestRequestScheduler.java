package org.dromara.quant.exchange.binance;

import org.dromara.quant.exchange.ExchangeMarketPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;

/**
 * 基于请求权重的全局令牌桶，并在环境和请求类别之间公平调度。
 */
final class RestRequestScheduler {
    private static final Logger log = LoggerFactory.getLogger(RestRequestScheduler.class);
    private static final double TOKEN_CAPACITY = 40D;
    private static final long RESERVATION_WAIT_NANOS = TimeUnit.MILLISECONDS.toNanos(250L);
    private static final long AGING_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(2L);
    private final ArrayDeque<Ticket>[][] queues;
    private final int[] rotation = {0, 1, 0, 2, 0, 0, 1, 3, 0, 4};
    /** 每个权重单位补充所需时间；生产环境 50ms 即每秒最多补充 20 个权重单位。 */
    private final long refillInterval;
    private double availableTokens = TOKEN_CAPACITY;
    private long lastRefill = System.nanoTime();
    private long blockedUntil;
    private boolean manualBlock;
    private int cursor;
    private int environmentCursor;

    @SuppressWarnings("unchecked")
    RestRequestScheduler(long refillIntervalMillis) {
        refillInterval = TimeUnit.MILLISECONDS.toNanos(refillIntervalMillis);
        queues = new ArrayDeque[Kind.values().length][Environment.values().length];
        for (int kind = 0; kind < queues.length; kind++) {
            for (int environment = 0; environment < Environment.values().length; environment++) {
                queues[kind][environment] = new ArrayDeque<>();
            }
        }
    }

    synchronized void acquire(Kind kind) {
        acquire("fapi.binance.com", kind, 1);
    }

    synchronized void acquire(Kind kind, int requestWeight) {
        acquire("fapi.binance.com", kind, requestWeight);
    }

    synchronized void acquire(String host, Kind kind, int requestWeight) {
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
        Environment environment = Environment.fromHost(host);
        if (queueSize(kind) >= 16) {
            throw deferred("QUEUE_FULL", kind, 0L, 2_000L);
        }
        Ticket ticket = new Ticket(kind, environment, requestWeight, initialNow);
        ArrayDeque<Ticket> queue = queues[kind.ordinal()][environment.ordinal()];
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
                        log.error("Binance REST 调度器队列状态异常 reason=QUEUE_TIMEOUT kind={} environment={} requestWeight={} waitedMs={} queueSizes={}",
                            kind, environment, requestWeight, TimeUnit.NANOSECONDS.toMillis(now - initialNow), java.util.Arrays.toString(queueSizes()));
                    }
                    throw deferred("QUEUE_TIMEOUT", kind, TimeUnit.NANOSECONDS.toMillis(now - initialNow), 1_500L);
                }
                refillTokens(now);
                Ticket selected = selectEligibleTicket(now);
                if (selected != null && selected.kind() == kind && selected.environment() == environment && queue.peekFirst() == ticket) {
                    queue.removeFirst();
                    availableTokens -= requestWeight;
                    cursor = selected.rotationIndex() + 1;
                    environmentCursor = (environment.ordinal() + 1) % Environment.values().length;
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

    private Ticket selectEligibleTicket(long now) {
        Ticket reservation = reservationTicket(now);
        for (int i = 0; i < rotation.length; i++) {
            int selected = (cursor + i) % rotation.length;
            Kind kind = Kind.values()[rotation[selected]];
            for (int offset = 0; offset < Environment.values().length; offset++) {
                Environment environment = Environment.values()[(environmentCursor + offset) % Environment.values().length];
                Ticket head = queues[kind.ordinal()][environment.ordinal()].peekFirst();
                if (head == null || head.weight() > availableTokens) {
                    continue;
                }
                if (reservedForAnother(head, reservation, now)) {
                    continue;
                }
                return head.withRotationIndex(selected);
            }
        }
        return null;
    }

    private boolean reservedForAnother(Ticket candidate, Ticket reservation, long now) {
        return reservation != null && candidate != reservation
            && effectivePriority(candidate, now) >= effectivePriority(reservation, now)
            && availableTokens - candidate.weight() < reservation.weight();
    }

    private long nextEligibleTokenDelayNanos(long now) {
        Ticket reservation = reservationTicket(now);
        int minimumWeight = Integer.MAX_VALUE;
        for (Kind kind : Kind.values()) {
            for (Environment environment : Environment.values()) {
                Ticket head = queues[kind.ordinal()][environment.ordinal()].peekFirst();
                if (head == null || reservedForAnother(head, reservation, now)) {
                    continue;
                }
                minimumWeight = Math.min(minimumWeight, head.weight());
            }
        }
        if (minimumWeight == Integer.MAX_VALUE || minimumWeight <= availableTokens) {
            return TimeUnit.MILLISECONDS.toNanos(10);
        }
        return Math.max(1L, (long) Math.ceil((minimumWeight - availableTokens) * refillInterval));
    }

    /** 关键请求立即预留；其他请求等待250ms后参与预留，并通过等待时长逐级提升优先级。 */
    private Ticket reservationTicket(long now) {
        Ticket reservation = null;
        for (Kind kind : Kind.values()) {
            for (Environment environment : Environment.values()) {
                Ticket head = queues[kind.ordinal()][environment.ordinal()].peekFirst();
                if (head == null) {
                    continue;
                }
                boolean critical = priority(kind) <= priority(Kind.POSITION_VERIFY);
                if (!critical && now - head.enqueuedAtNanos() < RESERVATION_WAIT_NANOS) {
                    continue;
                }
                if (reservation == null || effectivePriority(head, now) < effectivePriority(reservation, now)
                    || effectivePriority(head, now) == effectivePriority(reservation, now)
                        && head.enqueuedAtNanos() < reservation.enqueuedAtNanos()) {
                    reservation = head;
                }
            }
        }
        return reservation;
    }

    private static int effectivePriority(Ticket ticket, long now) {
        long waited = Math.max(0L, now - ticket.enqueuedAtNanos());
        int minimumPriority = ticket.kind() == Kind.PROTECTION_VERIFY ? 0 : 1;
        int aging = (int) Math.min(priority(ticket.kind()) - minimumPriority, waited / AGING_INTERVAL_NANOS);
        return priority(ticket.kind()) - aging;
    }

    private static int priority(Kind kind) {
        return switch (kind) {
            case PROTECTION_VERIFY -> 0;
            case TRADE, POSITION_VERIFY -> 1;
            case ACCOUNT -> 2;
            case BACKGROUND -> 3;
        };
    }

    private int queueSize(Kind kind) {
        int size = 0;
        for (Environment environment : Environment.values()) {
            size += queues[kind.ordinal()][environment.ordinal()].size();
        }
        return size;
    }

    private int[] queueSizes() {
        int[] sizes = new int[queues.length];
        for (Kind kind : Kind.values()) {
            sizes[kind.ordinal()] = queueSize(kind);
        }
        return sizes;
    }

    private ExchangeMarketPort.RequestDeferredException deferred(String reason, Kind kind, long waitedMillis, long retryAfterMillis) {
        int[] sizes = queueSizes();
        return new ExchangeMarketPort.RequestDeferredException(reason, kind.name(), sizes[kind.ordinal()],
            sizes, waitedMillis, retryAfterMillis);
    }

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

    private record Ticket(Kind kind, Environment environment, int weight, long enqueuedAtNanos, int rotationIndex) {
        private Ticket(Kind kind, Environment environment, int weight, long enqueuedAtNanos) {
            this(kind, environment, weight, enqueuedAtNanos, -1);
        }

        private Ticket withRotationIndex(int index) {
            return new Ticket(kind, environment, weight, enqueuedAtNanos, index);
        }
    }

    private enum Environment {
        LIVE, TESTNET;

        private static Environment fromHost(String host) {
            String normalized = host == null ? "" : host.toLowerCase(java.util.Locale.ROOT);
            return normalized.contains("testnet") || normalized.startsWith("demo-")
                ? TESTNET : LIVE;
        }
    }

    /** 单一全局令牌桶按每秒20个IP权重单位补充、最多积累40个单位。 */
    enum Kind {TRADE, PROTECTION_VERIFY, POSITION_VERIFY, ACCOUNT, BACKGROUND}
}
