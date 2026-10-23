package org.dromara.quant.market;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.dromara.quant.credential.domain.QuantCredential;
import org.dromara.quant.decision.TradeDecisionLogService;
import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.exchange.ExchangeMarketPort;
import org.dromara.quant.execution.TradeExecutionApplicationService;
import org.dromara.quant.execution.TradingPreparationService;
import org.dromara.quant.reconcile.LiveUserStreamLeaseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * 按账户复用用户数据流，并统一处理 keepalive / 失效重建。
 */
@RequiredArgsConstructor
@Component
@Slf4j
public class UserDataStreamHub {
    private static final long LISTEN_KEY_SAFE_RENEWAL_MILLIS = TimeUnit.MINUTES.toMillis(55);
    /**
     * 交易所统一网关。
     */
    private final ExchangeGateway gateway;
    /**
     * 交易执行应用服务。
     */
    private final TradeExecutionApplicationService executionService;
    private final AccountPositionCache positionCache;
    private final AccountBalanceCache balanceCache;
    private final TradingPreparationService preparationService;
    /**
     * 账户用户数据流保活与健康检查调度器。
     */
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "quant-userdata-scheduler");
        thread.setDaemon(true);
        return thread;
    });
    /**
     * 共享底层流连接缓存。
     */
    private final Map<Long, SharedUserStream> streams = new ConcurrentHashMap<>();
    private final Map<Long, Long> localLeaseEpochs = new ConcurrentHashMap<>();
    @Autowired
    private TradeDecisionLogService decisionLogs;
    @Autowired
    private LiveUserStreamLeaseService streamLease;
    @Autowired(required = false)
    private org.dromara.quant.reconcile.LivePositionEventCoordinator positionEvents;

    /**
     * Called only after the resident subscriber acquired the DB lease.
     */
    public void registerLeaseOwner(Long credentialId, long epoch) {
        streamLease.requireCurrentEpoch(credentialId, epoch);
        localLeaseEpochs.put(credentialId, epoch);
    }

    /**
     * 注销指定账户的用户流租约持有者。
     */
    public void unregisterLeaseOwner(Long credentialId, long epoch) {
        if (!localLeaseEpochs.remove(credentialId, epoch)) {
            return;
        }
        // Closing only the supervisor's subscription is insufficient when an
        // SSE browser still has a listener: the Binance socket would survive.
        // A revoked LIVE lease invalidates every local consumer of this stream.
        streams.computeIfPresent(credentialId, (id, stream) -> {
            if ("LIVE".equalsIgnoreCase(stream.credential.getRunMode())) {
                stream.close();
                return null;
            }
            return stream;
        });
        if (positionEvents != null) {
            positionEvents.onUserDataEvent(credentialId, new ExchangeGateway.UserDataEvent("DISCONNECTED", Instant.now(), null));
        }
    }

    /**
     * Whether the local Hub still retains the shared stream. A connection that
     * is temporarily reconnecting is still retained and must not be duplicated.
     * A stream forcibly removed after lease loss is no longer a valid enrollment.
     */
    public boolean hasLocalStream(Long credentialId) {
        SharedUserStream stream = streams.get(credentialId);
        return stream != null && !stream.closed;
    }

    /**
     * Only the node holding the current LIVE private-stream DB lease may start a strategy subscription.
     */
    public boolean hasLiveStreamOwnership(Long credentialId) {
        return credentialId != null && ownsLiveStream(credentialId);
    }

    private boolean ownsLiveStream(Long credentialId) {
        Long epoch = localLeaseEpochs.get(credentialId);
        return epoch != null && streamLease.owns(credentialId, epoch);
    }

    /**
     * 订阅指定事件流。
     */
    public AutoCloseable subscribe(QuantCredential credential, Consumer<ExchangeGateway.UserDataEvent> listener) {
        if (credential == null || credential.getId() == null) {
            throw new IllegalArgumentException("用户数据流缺少凭证");
        }
        if ("LIVE".equalsIgnoreCase(credential.getRunMode()) && !ownsLiveStream(credential.getId())) {
            throw new IllegalStateException("LIVE private stream belongs to another node or no lease owner is active");
        }
        final AutoCloseable[] subscription = new AutoCloseable[1];
        streams.compute(credential.getId(), (id, current) -> {
            SharedUserStream stream = current == null ? new SharedUserStream(credential) : current;
            subscription[0] = stream.subscribe(listener);
            return stream;
        });
        return subscription[0];
    }

    /**
     * 检查连接健康状态，必要时触发重连。
     */
    @Scheduled(fixedDelay = 15000, initialDelay = 20000)
    public void healthCheck() {
        streams.forEach((id, stream) -> {
            if ("LIVE".equalsIgnoreCase(stream.credential.getRunMode())) {
                boolean currentOwner;
                try {
                    currentOwner = ownsLiveStream(id);
                } catch (RuntimeException ex) {
                    // A missing/failed lease verification is not permission
                    // to keep a Binance private connection alive.
                    currentOwner = false;
                    log.warn("LIVE user-stream lease health verification failed credentialId={}", id, ex);
                }
                if (!currentOwner) {
                    if (streams.remove(id, stream)) {
                        stream.close();
                        if (positionEvents != null) {
                            positionEvents.onUserDataEvent(id, new ExchangeGateway.UserDataEvent("DISCONNECTED", Instant.now(), null));
                        }
                        log.warn("Closed obsolete LIVE private stream after lease loss credentialId={}", id);
                    }
                    return;
                }
            }
            stream.checkHealth();
        });
    }

    /**
     * 应用关闭时释放资源。
     */
    @PreDestroy
    public void destroy() {
        streams.values().forEach(SharedUserStream::close);
        streams.clear();
        scheduler.shutdownNow();
    }

    /**
     * SharedUserStream，量化交易模块领域/基础设施对象。
     */
    private final class SharedUserStream {
        /**
         * 当前运行上下文使用的交易凭证。
         */
        private final QuantCredential credential;
        /**
         * 当前共享流消费者集合。
         */
        private final CopyOnWriteArrayList<StreamEventConsumer<ExchangeGateway.UserDataEvent>> listeners = new CopyOnWriteArrayList<>();
        private volatile long generation;
        /**
         * 账户用户数据流会话。
         */
        private volatile ExchangeGateway.UserDataStreamSession session;
        /**
         * 最近一次收到有效事件的时间。
         */
        private volatile Instant lastEventAt = Instant.now();
        /**
         * 用户数据流 listenKey 保活任务。
         */
        private volatile ScheduledFuture<?> keepaliveTask;
        private volatile ScheduledFuture<?> keepaliveRetryTask;
        private volatile ScheduledFuture<?> startRetryTask;
        private volatile long startRetryNotBeforeNanos;
        private volatile Instant lastKeepaliveSuccessAt;
        /**
         * 组件/Runtime 是否已经关闭。
         */
        private volatile boolean closed;

        private SharedUserStream(QuantCredential credential) {
            this.credential = credential;
        }

        private synchronized AutoCloseable subscribe(Consumer<ExchangeGateway.UserDataEvent> listener) {
            if (closed) {
                throw new IllegalStateException("用户数据流已关闭");
            }
            StreamEventConsumer<ExchangeGateway.UserDataEvent> consumer = new StreamEventConsumer<>(2048, listener, ex -> {
                positionCache.disconnect(credential.getId());
                balanceCache.disconnect(credential.getId());
                preparationService.invalidate(credential.getId());
                log.warn("用户事件消费者失败 credentialId={}", credential.getId(), ex);
                scheduler.execute(() -> onEvent(new ExchangeGateway.UserDataEvent("DISCONNECTED", Instant.now(), null)));
            }, () -> scheduler.execute(() -> onEvent(new ExchangeGateway.UserDataEvent("DISCONNECTED", Instant.now(), null))));
            listeners.add(consumer);
            if (session == null) {
                start();
            }
            return () -> streams.computeIfPresent(credential.getId(), (id, stream) -> {
                if (stream != this) {
                    return stream;
                }
                listeners.remove(consumer);
                consumer.close();
                if (listeners.isEmpty()) {
                    close();
                    return null;
                }
                return stream;
            });
        }

        private synchronized void start() {
            if (closed || session != null) {
                return;
            }
            if (System.nanoTime() < startRetryNotBeforeNanos) {
                return;
            }
            if ("LIVE".equalsIgnoreCase(credential.getRunMode()) && !ownsLiveStream(credential.getId())) {
                return;
            }
            try {
                long attempt = ++generation;
                Instant listenKeyCreatedAt = Instant.now();
                ExchangeGateway.Credential exchangeCredential = executionService.toCredential(credential);
                positionCache.begin(credential.getId(), exchangeCredential, () -> {
                    ExchangeGateway.UserDataStreamSession currentSession = session;
                    return !closed && attempt == generation && currentSession != null && currentSession.isConnected();
                });
                balanceCache.begin(credential.getId(), exchangeCredential, () -> !closed && attempt == generation && session != null && session.isConnected());
                session = gateway.subscribeUserData(exchangeCredential, event -> {
                    synchronized (this) {
                        if (!closed && attempt == generation && (!"LIVE".equalsIgnoreCase(credential.getRunMode()) || ownsLiveStream(credential.getId()))) {
                            onEvent(event);
                        }
                    }
                });
                // Opening a Binance connection can block longer than the DB
                // lease. Never publish CONNECTED or retain a session if another
                // node took ownership while the network request was in flight.
                if ("LIVE".equalsIgnoreCase(credential.getRunMode()) && !ownsLiveStream(credential.getId())) {
                    ExchangeGateway.UserDataStreamSession obsolete = session;
                    session = null;
                    if (obsolete != null) {
                        obsolete.close();
                    }
                    throw new IllegalStateException("LIVE private stream lease lost during connection establishment");
                }
                lastEventAt = Instant.now();
                lastKeepaliveSuccessAt = listenKeyCreatedAt;
                onEvent(new ExchangeGateway.UserDataEvent("CONNECTED", lastEventAt, null));
                if (keepaliveTask != null) {
                    keepaliveTask.cancel(false);
                }
                keepaliveTask = scheduler.scheduleAtFixedRate(this::keepAlive, 30, 30, TimeUnit.MINUTES);
            } catch (RuntimeException ex) {
                log.error("用户数据流启动失败 credentialId={}: {}", credential.getId(), ex.getMessage(), ex);
                // The socket may already have been created when initial event
                // processing or keepalive scheduling fails. Never discard the
                // reference without closing the underlying private connection.
                ExchangeGateway.UserDataStreamSession failedSession = session;
                session = null;
                ++generation;
                if (keepaliveTask != null) {
                    keepaliveTask.cancel(false);
                    keepaliveTask = null;
                }
                if (failedSession != null) {
                    try {
                        failedSession.close();
                    } catch (RuntimeException closeError) {
                        log.warn("用户数据流启动失败后的连接关闭异常 credentialId={}", credential.getId(), closeError);
                    }
                }
                positionCache.disconnect(credential.getId());
                balanceCache.disconnect(credential.getId());
                preparationService.invalidate(credential.getId());
                long retryAfterMillis = throttleRetryAfterMillis(ex);
                if (retryAfterMillis > 0 && !closed && !listeners.isEmpty()) {
                    scheduleStartRetry(retryAfterMillis);
                    log.warn("用户数据流启动被 REST 调度或交易所限流延后，按退避时间重试 credentialId={} errorType={} retryAfterMs={}",
                        credential.getId(), ex.getClass().getSimpleName(), retryAfterMillis);
                }
            }
        }

        private void onEvent(ExchangeGateway.UserDataEvent event) {
            if ("LIVE".equalsIgnoreCase(credential.getRunMode()) && positionEvents != null) {
                positionEvents.onUserDataEvent(credential.getId(), event);
            }
            if (decisionLogs != null) {
                decisionLogs.privateEvent(credential.getId(), event);
            }
            lastEventAt = Instant.now();
            positionCache.onEvent(credential.getId(), event);
            balanceCache.onEvent(credential.getId(), event);
            preparationService.onEvent(credential.getId(), event);
            listeners.forEach(listener -> {
                try {
                    listener.accept(event);
                } catch (RuntimeException ex) {
                    log.warn("用户数据流消费者异常 credentialId={}: {}", credential.getId(), ex.getMessage());
                }
            });
            if (event != null && ("listenKeyExpired".equals(event.eventType()) || "DISCONNECTED".equals(event.eventType()))) {
                scheduler.execute(this::restart);
            }
        }

        private synchronized void keepAlive() {
            ExchangeGateway.UserDataStreamSession current = session;
            if (current == null) {
                return;
            }
            if (!current.isConnected()) {
                restart();
                return;
            }
            Instant lastSuccess = lastKeepaliveSuccessAt;
            if (lastSuccess == null || !Instant.now().isBefore(lastSuccess.plusMillis(LISTEN_KEY_SAFE_RENEWAL_MILLIS))) {
                log.warn("用户数据流保活超过安全续期间隔，失效账户缓存并重建 credentialId={}", credential.getId());
                restart();
                return;
            }
            try {
                current.keepAlive();
                lastKeepaliveSuccessAt = Instant.now();
                cancelKeepaliveRetry();
            } catch (RuntimeException ex) {
                long retryAfterMillis = throttleRetryAfterMillis(ex);
                if (retryAfterMillis > 0 && current == session && !closed) {
                    long safeDeadlineRemaining = Math.max(0L, java.time.Duration.between(Instant.now(), lastSuccess.plusMillis(LISTEN_KEY_SAFE_RENEWAL_MILLIS)).toMillis());
                    if (safeDeadlineRemaining > 0) {
                        long jitterMillis = ThreadLocalRandom.current().nextLong(0, 1_001L);
                        long retryDelayMillis = Math.max(1L, Math.min(safeDeadlineRemaining, retryAfterMillis + jitterMillis));
                        scheduleKeepaliveRetry(retryDelayMillis);
                        log.warn("用户数据流保活被 REST 调度或交易所限流延后，保留当前连接并限时重试 credentialId={} errorType={} retryAfterMs={} retryInMs={} safeDeadlineMs={}",
                            credential.getId(), ex.getClass().getSimpleName(), retryAfterMillis, retryDelayMillis, safeDeadlineRemaining);
                        return;
                    }
                }
                log.warn("用户数据流保活失败 credentialId={}: {}", credential.getId(), ex.getMessage());
                restart();
            }
        }

        private void scheduleKeepaliveRetry(long delayMillis) {
            cancelKeepaliveRetry();
            keepaliveRetryTask = scheduler.schedule(() -> {
                synchronized (SharedUserStream.this) {
                    keepaliveRetryTask = null;
                }
                keepAlive();
            }, delayMillis, TimeUnit.MILLISECONDS);
        }

        private void cancelKeepaliveRetry() {
            ScheduledFuture<?> retry = keepaliveRetryTask;
            keepaliveRetryTask = null;
            if (retry != null) {
                retry.cancel(false);
            }
        }

        private void scheduleStartRetry(long delayMillis) {
            if (startRetryTask != null) {
                startRetryTask.cancel(false);
            }
            startRetryNotBeforeNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMillis);
            startRetryTask = scheduler.schedule(() -> {
                synchronized (SharedUserStream.this) {
                    startRetryTask = null;
                    startRetryNotBeforeNanos = 0L;
                    start();
                }
            }, delayMillis, TimeUnit.MILLISECONDS);
        }

        private long throttleRetryAfterMillis(Throwable error) {
            for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                if (cause instanceof ExchangeMarketPort.RequestDeferredException deferred) {
                    return deferred.retryAfterMillis();
                }
                if (cause instanceof ExchangeMarketPort.RateLimitException rateLimit) {
                    return rateLimit.retryAfterMillis();
                }
            }
            return 0L;
        }

        private synchronized void checkHealth() {
            ExchangeGateway.UserDataStreamSession current = session;
            Instant lastSuccess = lastKeepaliveSuccessAt;
            if (current == null && !listeners.isEmpty()) {
                start();
            } else if (current != null && (lastSuccess == null || !Instant.now().isBefore(lastSuccess.plusMillis(LISTEN_KEY_SAFE_RENEWAL_MILLIS)))) {
                log.warn("用户数据流未能在安全期限内续期，失效账户缓存并重建 credentialId={}", credential.getId());
                restart();
            } else if (current != null && !current.isConnected()) {
                onEvent(new ExchangeGateway.UserDataEvent("DISCONNECTED", Instant.now(), null));
            }
            // 不根据业务事件空闲时间判断健康：账户长时间无成交/资金变化时属于正常状态。
            // listenKey 生命周期通过定时 keepalive 维护；SDK 连接异常通过事件/会话关闭路径触发重建。
        }

        private synchronized void restart() {
            positionCache.disconnect(credential.getId());
            balanceCache.disconnect(credential.getId());
            preparationService.invalidate(credential.getId());
            ++generation;
            cancelKeepaliveRetry();
            if (startRetryTask != null) {
                startRetryTask.cancel(false);
                startRetryTask = null;
            }
            startRetryNotBeforeNanos = 0L;
            lastKeepaliveSuccessAt = null;
            ExchangeGateway.UserDataStreamSession current = session;
            session = null;
            if (current != null) {
                try {
                    current.close();
                } catch (RuntimeException ignored) {
                }
            }
            if (!closed && !listeners.isEmpty()) {
                scheduler.schedule(this::start, 3, TimeUnit.SECONDS);
            }
        }

        private synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            positionCache.remove(credential.getId());
            balanceCache.remove(credential.getId());
            preparationService.remove(credential.getId());
            ++generation;
            cancelKeepaliveRetry();
            if (startRetryTask != null) {
                startRetryTask.cancel(false);
                startRetryTask = null;
            }
            startRetryNotBeforeNanos = 0L;
            lastKeepaliveSuccessAt = null;
            listeners.forEach(StreamEventConsumer::close);
            listeners.clear();
            if (keepaliveTask != null) {
                keepaliveTask.cancel(false);
            }
            ExchangeGateway.UserDataStreamSession current = session;
            session = null;
            if (current != null) {
                try {
                    current.close();
                } catch (RuntimeException ignored) {
                }
            }
        }
    }
}
