package org.dromara.quant.market;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.dromara.quant.credential.domain.QuantCredential;
import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.execution.TradeExecutionApplicationService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * 按账户复用用户数据流，并统一处理 keepalive / 失效重建。
 */
@Component
@Slf4j
public class UserDataStreamHub {
    @org.springframework.beans.factory.annotation.Autowired
    private org.dromara.quant.decision.TradeDecisionLogService decisionLogs;

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
    private final org.dromara.quant.execution.TradingPreparationService preparationService;
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
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.dromara.quant.reconcile.LivePositionEventCoordinator positionEvents;


    /**
     * 创建 UserDataStreamHub 实例并初始化其依赖。
     */
    public UserDataStreamHub(ExchangeGateway gateway, TradeExecutionApplicationService executionService, AccountPositionCache positionCache, AccountBalanceCache balanceCache, org.dromara.quant.execution.TradingPreparationService preparationService) {
        this.gateway = gateway;
        this.executionService = executionService;
        this.positionCache = positionCache;
        this.balanceCache = balanceCache;
        this.preparationService = preparationService;
    }

    /**
     * 订阅指定事件流。
     */
    public AutoCloseable subscribe(QuantCredential credential, Consumer<ExchangeGateway.UserDataEvent> listener) {
        if (credential == null || credential.getId() == null) {
            throw new IllegalArgumentException("用户数据流缺少凭证");
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
        streams.values().forEach(SharedUserStream::checkHealth);
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
            StreamEventConsumer<ExchangeGateway.UserDataEvent> consumer = new StreamEventConsumer<>(2048, listener,
                ex -> {
                    positionCache.disconnect(credential.getId());
                    balanceCache.disconnect(credential.getId());
                    preparationService.invalidate(credential.getId());
                    log.warn("用户事件消费者失败 credentialId={}", credential.getId(), ex);
                    scheduler.execute(() -> onEvent(new ExchangeGateway.UserDataEvent("DISCONNECTED", Instant.now(), null)));
                },
                () -> scheduler.execute(() -> onEvent(new ExchangeGateway.UserDataEvent("DISCONNECTED", Instant.now(), null))));
            listeners.add(consumer);
            if (session == null) {
                start();
            }
            return () -> streams.computeIfPresent(credential.getId(), (id, stream) -> {
                if (stream != this) { return stream; }
                listeners.remove(consumer);
                consumer.close();
                if (listeners.isEmpty()) { close(); return null; }
                return stream;
            });
        }

        private synchronized void start() {
            if (closed || session != null) {
                return;
            }
            try {
                long attempt = ++generation;
                ExchangeGateway.Credential exchangeCredential = executionService.toCredential(credential);
                positionCache.begin(credential.getId(), exchangeCredential,
                    () -> {
                        ExchangeGateway.UserDataStreamSession currentSession = session;
                        return !closed && attempt == generation && currentSession != null && currentSession.isConnected();
                    });
                balanceCache.begin(credential.getId(), exchangeCredential,
                    () -> !closed && attempt == generation && session != null && session.isConnected());
                session = gateway.subscribeUserData(exchangeCredential, event -> {
                    synchronized (this) {
                        if (!closed && attempt == generation) { onEvent(event); }
                    }
                });
                lastEventAt = Instant.now();
                onEvent(new ExchangeGateway.UserDataEvent("CONNECTED", lastEventAt, null));
                if (keepaliveTask != null) {
                    keepaliveTask.cancel(false);
                }
                keepaliveTask = scheduler.scheduleAtFixedRate(this::keepAlive, 30, 30, TimeUnit.MINUTES);
            } catch (RuntimeException ex) {
                log.error("用户数据流启动失败 credentialId={}: {}", credential.getId(), ex.getMessage(), ex);
                session = null;
                positionCache.disconnect(credential.getId());
                balanceCache.disconnect(credential.getId());
                preparationService.invalidate(credential.getId());
            }
        }

        private void onEvent(ExchangeGateway.UserDataEvent event) {
            if ("LIVE".equalsIgnoreCase(credential.getRunMode()) && positionEvents != null) {
                positionEvents.onUserDataEvent(credential.getId(), event);
            }
            if (decisionLogs != null) { decisionLogs.privateEvent(credential.getId(), event); }
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

        private void keepAlive() {
            ExchangeGateway.UserDataStreamSession current = session;
            if (current == null) {
                return;
            }
            try {
                current.keepAlive();
            } catch (RuntimeException ex) {
                log.warn("用户数据流保活失败 credentialId={}: {}", credential.getId(), ex.getMessage());
                restart();
            }
        }

        private void checkHealth() {
            ExchangeGateway.UserDataStreamSession current = session;
            if (current == null && !listeners.isEmpty()) {
                start();
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
