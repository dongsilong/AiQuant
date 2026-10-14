package org.dromara.quant.market.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.quant.credential.domain.QuantCredential;
import org.dromara.quant.credential.mapper.QuantCredentialMapper;
import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.execution.TradeExecutionApplicationService;
import org.dromara.quant.market.UserDataStreamHub;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

/**
 * 将账户私有事件转发给交易终端。底层连接复用 UserDataStreamHub，避免每个浏览器重复建立交易所连接。
 */
@RestController
@RequiredArgsConstructor
@Slf4j
@RequestMapping("/quant/account")
public class QuantAccountStreamController {
    private final UserDataStreamHub userDataStreamHub;
    private final QuantCredentialMapper credentialMapper;
    private final ExchangeGateway gateway;
    private final TradeExecutionApplicationService executionService;
    private final ScheduledExecutorService heartbeatScheduler = Executors.newScheduledThreadPool(1, runnable -> {
        Thread thread = new Thread(runnable, "quant-account-sse-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    @SaCheckPermission("quant:terminal:list")
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(HttpServletResponse response, @RequestParam Long credentialId) {
        QuantCredential credential = credentialMapper.selectById(credentialId);
        if (!accessible(credential)) {
            throw new IllegalArgumentException("无权访问交易账户");
        }
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        SseEmitter emitter = new SseEmitter(0L);
        AtomicReference<AutoCloseable> subscription = new AtomicReference<>();
        AtomicReference<ScheduledFuture<?>> heartbeat = new AtomicReference<>();
        Runnable cleanup = () -> {
            ScheduledFuture<?> task = heartbeat.getAndSet(null);
            if (task != null) {
                task.cancel(false);
            }
            AutoCloseable active = subscription.getAndSet(null);
            if (active != null) {
                try {
                    active.close();
                } catch (Exception ignored) {
                    // 客户端断开后释放共享订阅。
                }
            }
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(error -> cleanup.run());
        try {
            emitter.send(SseEmitter.event().name("quant.account").data(Map.of(
                "type", "CONNECTING", "credentialId", credentialId.toString()
            )));
            heartbeat.set(heartbeatScheduler.scheduleAtFixedRate(() -> {
                try {
                    emitter.send(SseEmitter.event().comment("keepalive"));
                } catch (IOException | RuntimeException ex) {
                    cleanup.run();
                }
            }, 15, 15, TimeUnit.SECONDS));
            subscription.set(userDataStreamHub.subscribe(credential, event -> send(emitter, cleanup, credential, event)));
            send(emitter, cleanup, credential, new ExchangeGateway.UserDataEvent("SNAPSHOT", Instant.now(), null));
        } catch (IOException | RuntimeException ex) {
            cleanup.run();
        }
        return emitter;
    }

    @PreDestroy
    public void destroy() {
        heartbeatScheduler.shutdownNow();
    }

    private void send(SseEmitter emitter, Runnable cleanup, QuantCredential credential, ExchangeGateway.UserDataEvent event) {
        if (event == null || !("ACCOUNT_UPDATE".equals(event.eventType()) || "ORDER_TRADE_UPDATE".equals(event.eventType())
            || "ALGO_UPDATE".equals(event.eventType()) || "CONNECTED".equals(event.eventType()) || "SNAPSHOT".equals(event.eventType()) || "DISCONNECTED".equals(event.eventType()))) {
            return;
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", event.eventType());
        payload.put("credentialId", credential.getId().toString());
        payload.put("eventTime", event.eventTime() == null ? Instant.now().toString() : event.eventTime().toString());
        if ("SNAPSHOT".equals(event.eventType())) {
            ExchangeGateway.Credential exchangeCredential = executionService.toCredential(credential);
            boolean available = false;
            try {
                payload.put("account", gateway.queryAccount(exchangeCredential));
                available = true;
            } catch (RuntimeException ex) {
                log.warn("账户 SSE 初始资金快照失败 credentialId={} error={}", credential.getId(), ex.getMessage());
            }
            try {
                payload.put("positions", gateway.queryPositions(exchangeCredential, null));
                available = true;
            } catch (RuntimeException ex) {
                log.warn("账户 SSE 初始持仓快照失败 credentialId={} error={}", credential.getId(), ex.getMessage());
            }
            try {
                payload.put("orders", gateway.queryOrders(exchangeCredential, null));
                available = true;
            } catch (RuntimeException ex) {
                log.warn("账户 SSE 初始委托快照失败 credentialId={} error={}", credential.getId(), ex.getMessage());
            }
            payload.put("snapshotAvailable", available);
        } else if ("ACCOUNT_UPDATE".equals(event.eventType()) || "ORDER_TRADE_UPDATE".equals(event.eventType()) || "ALGO_UPDATE".equals(event.eventType())) {
            // 实时事件直接转发原始用户数据，不再为每条事件查询三次 REST。
            payload.put("snapshotAvailable", true);
            payload.put("rawJson", event.rawJson());
        } else {
            payload.put("snapshotAvailable", !"DISCONNECTED".equals(event.eventType()));
        }
        try {
            emitter.send(SseEmitter.event().name("quant.account").data(payload));
        } catch (IOException | RuntimeException ex) {
            cleanup.run();
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
