package org.dromara.quant.market.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.quant.config.BeijingTime;
import org.dromara.quant.credential.domain.QuantCredential;
import org.dromara.quant.credential.mapper.QuantCredentialMapper;
import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.execution.TradeExecutionApplicationService;
import org.dromara.quant.market.UserDataStreamHub;
import org.dromara.quant.runtime.StrategyPositionPerformanceHub;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 将账户私有事件转发给交易终端。底层连接复用 UserDataStreamHub，避免每个浏览器重复建立交易所连接。
 */
@RestController
@RequiredArgsConstructor
@Slf4j
@RequestMapping("/quant/account")
public class QuantAccountStreamController {
    private final UserDataStreamHub userDataStreamHub;
    private final StrategyPositionPerformanceHub performanceHub;
    private final QuantCredentialMapper credentialMapper;
    private final ExchangeGateway gateway;
    private final TradeExecutionApplicationService executionService;
    private final ScheduledExecutorService heartbeatScheduler = Executors.newScheduledThreadPool(1, runnable -> {
        Thread thread = new Thread(runnable, "quant-account-sse-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 为有权访问的账户建立持仓与余额事件的 SSE 连接。
     */
    @SaCheckPermission("quant:terminal:list")
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(HttpServletRequest request, HttpServletResponse response, @RequestParam Long credentialId) {
        QuantCredential credential = credentialMapper.selectById(credentialId);
        if (!accessible(credential)) {
            throw new IllegalArgumentException("无权访问交易账户");
        }
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        SseEmitter emitter = new SseEmitter(0L);
        SseResponseReadyGate responseReady = new SseResponseReadyGate(request);
        AtomicBoolean closed = new AtomicBoolean();
        AtomicReference<AutoCloseable> accountSubscription = new AtomicReference<>();
        AtomicReference<AutoCloseable> performanceSubscription = new AtomicReference<>();
        AtomicReference<ScheduledFuture<?>> heartbeat = new AtomicReference<>();
        AtomicReference<SseEventDispatcher> dispatcher = new AtomicReference<>();
        Runnable cleanup = () -> {
            if (!closed.compareAndSet(false, true)) return;
            responseReady.close();
            SseEventDispatcher writer = dispatcher.getAndSet(null);
            if (writer != null) writer.close();
            ScheduledFuture<?> task = heartbeat.getAndSet(null);
            if (task != null) {
                task.cancel(false);
            }
            closeSubscription(accountSubscription.getAndSet(null));
            closeSubscription(performanceSubscription.getAndSet(null));
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(error -> cleanup.run());
        try {
            emitter.send(SseEmitter.event().name("quant.account").data(Map.of("type", "CONNECTING", "credentialId", credentialId.toString())));
            SseEventDispatcher writer = new SseEventDispatcher(emitter, responseReady, () -> {
                cleanup.run();
                emitter.complete();
            });
            dispatcher.set(writer);
            writer.start();
            heartbeat.set(heartbeatScheduler.scheduleAtFixedRate(() -> {
                if (!closed.get()) writer.offerComment("heartbeat", "keepalive");
            }, 15, 15, TimeUnit.SECONDS));
            performanceSubscription.set(performanceHub.subscribe(credentialId, event -> {
                if (!closed.get()) {
                    String key = "position-performance:" + event.instanceId() + ":" + event.positionCycleKey();
                    writer.offerLatest(key, "quant.account", Map.of(
                        "type", "POSITION_MAX_RETURN", "credentialId", credentialId.toString(), "performance", event));
                }
            }));
            // 建立交易所私有流和初始账户快照可能触发阻塞式网络请求，不能占用
            // Servlet 请求线程，否则异步响应尚未启动，SSE writer 会一直等待 responseReady。
            Thread.ofVirtual().name("quant-account-stream-init").start(() -> {
                try {
                    if (closed.get()) return;
                    AutoCloseable subscription = userDataStreamHub.subscribe(credential,
                        event -> send(payload -> writer.offer("quant.account", payload), credential, event));
                    accountSubscription.set(subscription);
                    if (closed.get()) {
                        closeSubscription(accountSubscription.getAndSet(null));
                        return;
                    }
                    send(payload -> writer.offer("quant.account", payload), credential,
                        new ExchangeGateway.UserDataEvent("SNAPSHOT", Instant.now(), null));
                } catch (RuntimeException ex) {
                    log.warn("账户 SSE 初始化失败 credentialId={}", credentialId, ex);
                    cleanup.run();
                    emitter.complete();
                }
            });
            if (closed.get()) {
                closeSubscription(accountSubscription.getAndSet(null));
                closeSubscription(performanceSubscription.getAndSet(null));
            }
        } catch (IOException | RuntimeException ex) {
            cleanup.run();
        }
        return emitter;
    }

    /**
     * 关闭控制器持有的 SSE 连接和清理任务。
     */
    @PreDestroy
    public void destroy() {
        heartbeatScheduler.shutdownNow();
    }

    private void send(Consumer<Map<String, Object>> deliver, QuantCredential credential, ExchangeGateway.UserDataEvent event) {
        if (event == null || !("ACCOUNT_UPDATE".equals(event.eventType()) || "ORDER_TRADE_UPDATE".equals(event.eventType()) || "ALGO_UPDATE".equals(event.eventType()) ||
            "CONNECTED".equals(event.eventType()) || "SNAPSHOT".equals(event.eventType()) || "DISCONNECTED".equals(event.eventType()))) {
            return;
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", event.eventType());
        payload.put("credentialId", credential.getId().toString());
        payload.put("eventTime", BeijingTime.format(event.eventTime() == null ? Instant.now() : event.eventTime()));
        if ("SNAPSHOT".equals(event.eventType())) {
            // 账户资金、当前持仓和委托均由终端按需通过 REST 加载，避免 SSE 初始快照重复消耗交易所请求权重。
            payload.put("positionsSnapshotSkipped", true);
            payload.put("accountSnapshotAvailable", false);
            payload.put("positionsSnapshotAvailable", false);
            payload.put("ordersSnapshotSkipped", true);
            payload.put("ordersSnapshotAvailable", false);
            payload.put("snapshotAvailable", false);
        } else if ("ACCOUNT_UPDATE".equals(event.eventType()) || "ORDER_TRADE_UPDATE".equals(event.eventType()) || "ALGO_UPDATE".equals(event.eventType())) {
            // 实时事件直接转发原始用户数据，不再为每条事件查询三次 REST。
            payload.put("snapshotAvailable", true);
            payload.put("rawJson", event.rawJson());
        } else {
            payload.put("snapshotAvailable", !"DISCONNECTED".equals(event.eventType()));
        }
        deliver.accept(payload);
    }

    private static void closeSubscription(AutoCloseable subscription) {
        if (subscription == null) return;
        try {
            subscription.close();
        } catch (Exception ignored) {
            // 客户端断开后释放共享订阅。
        }
    }

    private boolean accessible(QuantCredential credential) {
        if (credential == null) {
            return false;
        }
        Long userId = LoginHelper.getUserId();
        return LoginHelper.isSuperAdmin(userId) || (userId != null && userId.equals(credential.getUserId()));
    }
}
