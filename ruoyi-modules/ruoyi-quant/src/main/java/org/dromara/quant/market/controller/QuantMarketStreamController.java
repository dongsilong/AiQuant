package org.dromara.quant.market.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.dromara.quant.market.MarketStreamHub;
import org.dromara.quant.exchange.ExchangeGateway;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 将 Binance WebSocket 行情事件推送给交易终端。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/quant/market")
public class QuantMarketStreamController {
    private static final int MAX_BATCH_SUBSCRIPTIONS = 50;

    /**
     * 事件流中心。
     */
    private final MarketStreamHub hub;

    private static void addSubscription(List<AutoCloseable> subscriptions, AtomicBoolean closed, AutoCloseable subscription) {
        synchronized (subscriptions) {
            if (!closed.get()) {
                subscriptions.add(subscription);
                return;
            }
        }
        closeSubscription(subscription);
    }

    private static void closeSubscriptions(List<AutoCloseable> subscriptions) {
        subscriptions.forEach(QuantMarketStreamController::closeSubscription);
        subscriptions.clear();
    }

    private static void closeSubscription(AutoCloseable subscription) {
        try {
            subscription.close();
        } catch (Exception ignored) {
            // 客户端断开后释放共享订阅。
        }
    }

    /**
     * 建立实时行情流响应。
     */
    @SaCheckPermission("quant:terminal:list")
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(HttpServletRequest request, HttpServletResponse response, @RequestParam String symbol, @RequestParam(defaultValue = "15m") String interval,
                             @RequestParam(defaultValue = "LIVE") String runMode) {
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        SseEmitter emitter = new SseEmitter(0L);
        SseResponseReadyGate responseReady = new SseResponseReadyGate(request);
        AtomicReference<AutoCloseable> subscription = new AtomicReference<>();
        AtomicReference<SseEventDispatcher> dispatcher = new AtomicReference<>();
        SseReplayKlineBuffer replayBuffer = new SseReplayKlineBuffer();
        Runnable cleanup = () -> {
            responseReady.close();
            SseEventDispatcher activeDispatcher = dispatcher.getAndSet(null);
            if (activeDispatcher != null) activeDispatcher.close();
            AutoCloseable active = subscription.getAndSet(null);
            if (active != null) {
                try {
                    active.close();
                } catch (Exception ignored) {
                    // 客户端断开后释放订阅。
                }
            }
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(error -> cleanup.run());
        try {
            emitter.send(SseEmitter.event().name("quant.market").data(java.util.Map.of("type", "CONNECTING", "symbol", symbol, "interval", interval)));
        } catch (IOException ex) {
            cleanup.run();
            return emitter;
        }
        SseEventDispatcher delivery = new SseEventDispatcher(emitter, responseReady, () -> {
            cleanup.run();
            emitter.complete();
        });
        dispatcher.set(delivery);
        delivery.start();
        try {
            subscription.set(hub.subscribe(runMode, symbol, interval, event -> {
                replayBuffer.publish(event, item -> delivery.offer("quant.market", item));
            }, ignored -> { }));
        } catch (RuntimeException ex) {
            cleanup.run();
            try {
                emitter.send(SseEmitter.event().name("quant.market").data(java.util.Map.of("type", "ERROR", "message", "行情订阅失败")));
            } catch (IOException ignored) {
                // SSE 已无法继续发送。
            }
        }
        return emitter;
    }

    /**
     * 在一条浏览器 SSE 连接上复用多个交易对行情订阅。symbol 和 interval 按顺序一一对应。
     */
    @SaCheckPermission("quant:terminal:list")
    @GetMapping(value = "/streams", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streams(HttpServletRequest request, HttpServletResponse response, @RequestParam("symbol") List<String> symbols, @RequestParam("interval") List<String> intervals,
                              @RequestParam(defaultValue = "LIVE") String runMode) {
        validateSubscriptions(symbols, intervals);
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        SseEmitter emitter = new SseEmitter(0L);
        SseResponseReadyGate responseReady = new SseResponseReadyGate(request);
        AtomicBoolean closed = new AtomicBoolean();
        List<AutoCloseable> subscriptions = new ArrayList<>();
        AtomicReference<SseEventDispatcher> dispatcher = new AtomicReference<>();
        Runnable cleanup = () -> {
            responseReady.close();
            SseEventDispatcher activeDispatcher = dispatcher.getAndSet(null);
            if (activeDispatcher != null) activeDispatcher.close();
            List<AutoCloseable> active;
            synchronized (subscriptions) {
                if (!closed.compareAndSet(false, true)) {
                    return;
                }
                active = new ArrayList<>(subscriptions);
                subscriptions.clear();
            }
            closeSubscriptions(active);
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(error -> cleanup.run());
        try {
            emitter.send(SseEmitter.event().name("quant.market").data(java.util.Map.of("type", "CONNECTING")));
        } catch (IOException ex) {
            cleanup.run();
            return emitter;
        }
        SseEventDispatcher delivery = new SseEventDispatcher(emitter, responseReady, () -> {
            cleanup.run();
            emitter.complete();
        });
        dispatcher.set(delivery);
        delivery.start();
        for (int index = 0; index < symbols.size() && !closed.get(); index++) {
            String symbol = symbols.get(index).trim();
            String interval = intervals.get(index).trim();
            try {
                SseReplayKlineBuffer replayBuffer = new SseReplayKlineBuffer();
                AutoCloseable subscription = hub.subscribe(runMode, symbol, interval, event -> {
                    if (!closed.get()) replayBuffer.publish(event, item -> delivery.offer("quant.market", item));
                }, ignored -> { });
                addSubscription(subscriptions, closed, subscription);
            } catch (RuntimeException ex) {
                try {
                    emitter.send(SseEmitter.event().name("quant.market").data(java.util.Map.of("type", "ERROR", "symbol", symbol, "interval", interval, "message", "行情订阅失败")));
                } catch (IOException | RuntimeException sendError) {
                    cleanup.run();
                }
            }
        }
        return emitter;
    }

    private void validateSubscriptions(List<String> symbols, List<String> intervals) {
        if (symbols == null || intervals == null || symbols.isEmpty() || symbols.size() != intervals.size() || symbols.size() > MAX_BATCH_SUBSCRIPTIONS) {
            throw new IllegalArgumentException("行情订阅参数无效或订阅数量超限");
        }
        for (int index = 0; index < symbols.size(); index++) {
            String symbol = symbols.get(index);
            String interval = intervals.get(index);
            if (symbol == null || symbol.isBlank() || symbol.length() > 30 || interval == null || interval.isBlank() || interval.length() > 8) {
                throw new IllegalArgumentException("行情订阅参数无效");
            }
        }
    }
}
