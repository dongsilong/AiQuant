package org.dromara.quant.runtime.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.quant.credential.domain.QuantCredential;
import org.dromara.quant.credential.mapper.QuantCredentialMapper;
import org.dromara.quant.runtime.StrategyPositionPerformanceEvent;
import org.dromara.quant.runtime.StrategyPositionPerformanceHub;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Map;

/** 向交易终端推送策略持仓最大回报率变化。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/quant/strategy/position-performance")
public class StrategyPositionPerformanceController {
    private final StrategyPositionPerformanceHub hub;
    private final QuantCredentialMapper credentialMapper;
    private final ScheduledExecutorService heartbeatScheduler = Executors.newScheduledThreadPool(1, runnable -> {
        Thread thread = new Thread(runnable, "quant-position-performance-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    /** 建立账户级 SSE 连接；慢客户端不会阻塞策略行情消费线程。 */
    @SaCheckPermission("quant:terminal:list")
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(HttpServletResponse response, @RequestParam Long credentialId) {
        QuantCredential credential = credentialMapper.selectById(credentialId);
        Long userId = LoginHelper.getUserId();
        if (credential == null || (!LoginHelper.isSuperAdmin(userId) && (userId == null || !userId.equals(credential.getUserId())))) {
            throw new IllegalArgumentException("无权访问该交易账户");
        }
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        SseEmitter emitter = new SseEmitter(0L);
        ArrayBlockingQueue<StrategyPositionPerformanceEvent> queue = new ArrayBlockingQueue<>(256);
        AtomicBoolean closed = new AtomicBoolean();
        AtomicReference<AutoCloseable> subscription = new AtomicReference<>();
        AtomicReference<ScheduledFuture<?>> heartbeat = new AtomicReference<>();
        AtomicReference<Thread> worker = new AtomicReference<>();
        Runnable cleanup = () -> {
            if (!closed.compareAndSet(false, true)) return;
            AutoCloseable active = subscription.getAndSet(null);
            if (active != null) {
                try { active.close(); } catch (Exception ignored) { }
            }
            ScheduledFuture<?> beat = heartbeat.getAndSet(null);
            if (beat != null) beat.cancel(false);
            Thread thread = worker.get();
            if (thread != null) thread.interrupt();
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(error -> cleanup.run());
        try {
            subscription.set(hub.subscribe(credentialId, event -> {
                if (!closed.get() && !queue.offer(event)) {
                    // 峰值只增不减；队列满时断开并让客户端重连后从 REST 状态快照补齐。
                    emitter.complete();
                }
            }));
            emitter.send(SseEmitter.event().name("quant.position.performance").data(Map.of("type", "CONNECTED", "credentialId", credentialId)));
            heartbeat.set(heartbeatScheduler.scheduleAtFixedRate(() -> {
                try { emitter.send(SseEmitter.event().comment("keepalive")); }
                catch (IOException | RuntimeException ex) { cleanup.run(); }
            }, 15, 15, TimeUnit.SECONDS));
            Thread sender = Thread.ofVirtual().name("quant-position-performance-sse").start(() -> {
                while (!closed.get()) {
                    try {
                        StrategyPositionPerformanceEvent event = queue.take();
                        emitter.send(SseEmitter.event().name("quant.position.performance").data(event));
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (IOException | RuntimeException ex) {
                        cleanup.run();
                        break;
                    }
                }
            });
            worker.set(sender);
            if (closed.get()) sender.interrupt();
        } catch (IOException | RuntimeException ex) {
            cleanup.run();
            emitter.complete();
        }
        return emitter;
    }

    /** 释放控制器持有的心跳线程。 */
    @PreDestroy
    public void destroy() {
        heartbeatScheduler.shutdownNow();
    }
}
