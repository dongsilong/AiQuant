package org.dromara.quant.market.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.dromara.quant.market.MarketStreamHub;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 将 Binance WebSocket 行情事件推送给交易终端。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/quant/market")
public class QuantMarketStreamController {
    /**
     * 事件流中心。
     */
    private final MarketStreamHub hub;

    /**
     * 建立实时行情流响应。
     */
    @SaCheckPermission("quant:terminal:list")
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(HttpServletResponse response, @RequestParam String symbol, @RequestParam(defaultValue = "15m") String interval) {
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        SseEmitter emitter = new SseEmitter(0L);
        AtomicReference<AutoCloseable> subscription = new AtomicReference<>();
        Runnable cleanup = () -> {
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
        try {
            subscription.set(hub.subscribe(symbol, interval, event -> {
                try {
                    emitter.send(SseEmitter.event().name("quant.market").data(event));
                } catch (IOException | RuntimeException ex) {
                    cleanup.run();
                }
            }));
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
}
