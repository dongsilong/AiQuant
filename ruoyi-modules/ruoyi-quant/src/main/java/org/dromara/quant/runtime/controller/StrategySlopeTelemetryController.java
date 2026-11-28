package org.dromara.quant.runtime.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.quant.credential.domain.QuantCredential;
import org.dromara.quant.credential.mapper.QuantCredentialMapper;
import org.dromara.quant.runtime.StrategySlopeTelemetryHub;
import org.dromara.quant.runtime.StrategySlopeMonitorService;
import org.dromara.quant.strategy.domain.QuantStrategy;
import org.dromara.quant.strategy.mapper.QuantStrategyMapper;
import org.dromara.quant.strategy.service.QuantStrategyService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 将策略实际斜率计算结果推送给交易终端。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/quant/strategy")
public class StrategySlopeTelemetryController {
    private final StrategySlopeTelemetryHub hub;
    private final StrategySlopeMonitorService monitorService;
    private final QuantStrategyMapper strategyMapper;
    private final QuantCredentialMapper credentialMapper;
    private final QuantStrategyService strategyService;

    /**
     * 校验策略与账户访问权限后建立实时斜率 SSE 连接。
     */
    @SaCheckPermission("quant:terminal:list")
    @GetMapping(value = "/slope/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(HttpServletResponse response, @RequestParam Long strategyId, @RequestParam Long credentialId,
                             @RequestParam(required = false) String interval) {
        QuantStrategy strategy = strategyMapper.selectById(strategyId);
        strategyService.checkAccess(strategy);
        QuantCredential credential = credentialMapper.selectById(credentialId);
        Long userId = LoginHelper.getUserId();
        if (credential == null || (!LoginHelper.isSuperAdmin(userId) && !userId.equals(credential.getUserId()))) {
            throw new IllegalArgumentException("无权访问该交易账户");
        }
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        SseEmitter emitter = new SseEmitter(0L);
        AtomicReference<AutoCloseable> telemetrySubscription = new AtomicReference<>();
        AtomicReference<AutoCloseable> monitorSubscription = new AtomicReference<>();
        AtomicBoolean cleaned = new AtomicBoolean();
        Runnable cleanup = () -> {
            cleaned.set(true);
            AutoCloseable monitor = monitorSubscription.getAndSet(null);
            if (monitor != null) {
                try {
                    monitor.close();
                } catch (Exception ignored) {
                }
            }
            AutoCloseable telemetry = telemetrySubscription.getAndSet(null);
            if (telemetry != null) {
                try {
                    telemetry.close();
                } catch (Exception ignored) {
                }
            }
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(error -> cleanup.run());
        try {
            emitter.send(SseEmitter.event().name("quant.strategy.slope").data(Map.of("type", "CONNECTED", "strategyId", strategyId, "credentialId", credentialId)));
            telemetrySubscription.set(hub.subscribe(strategyId, credentialId, event -> {
                try {
                    emitter.send(SseEmitter.event().name("quant.strategy.slope").data(event));
                } catch (IOException | RuntimeException ex) {
                    cleanup.run();
                }
            }));
            if (cleaned.get()) {
                cleanup.run();
            }
            monitorSubscription.set(monitorService.subscribe(strategy, credential, interval));
            if (cleaned.get()) {
                cleanup.run();
            }
        } catch (IOException | RuntimeException ex) {
            cleanup.run();
        }
        return emitter;
    }
}
