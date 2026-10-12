package org.dromara.quant.runtime;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 按策略和账户分发策略实际斜率结果的进程内 SSE 事件中心。
 */
@Component
public class StrategySlopeTelemetryHub {
    private final Map<Key, Map<String, StrategySlopeTelemetry>> latest = new ConcurrentHashMap<>();
    private final Map<Key, CopyOnWriteArrayList<Consumer<StrategySlopeTelemetry>>> listeners = new ConcurrentHashMap<>();

    public AutoCloseable subscribe(Long strategyId, Long credentialId, Consumer<StrategySlopeTelemetry> listener) {
        Key key = new Key(strategyId, credentialId);
        listeners.computeIfAbsent(key, ignored -> new CopyOnWriteArrayList<>()).add(listener);
        latest.getOrDefault(key, Map.of()).values().forEach(listener);
        return () -> {
            CopyOnWriteArrayList<Consumer<StrategySlopeTelemetry>> current = listeners.get(key);
            if (current != null) {
                current.remove(listener);
                if (current.isEmpty()) listeners.remove(key, current);
            }
        };
    }

    public void publish(StrategySlopeTelemetry telemetry) {
        Key key = new Key(telemetry.strategyId(), telemetry.credentialId());
        latest.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>()).put(telemetry.symbol(), telemetry);
        for (Consumer<StrategySlopeTelemetry> listener : listeners.getOrDefault(key, new CopyOnWriteArrayList<>())) {
            try {
                listener.accept(telemetry);
            } catch (RuntimeException ignored) {
                // 单个 SSE 客户端异常不能影响策略运行时。
            }
        }
    }

    private record Key(Long strategyId, Long credentialId) {
    }
}
