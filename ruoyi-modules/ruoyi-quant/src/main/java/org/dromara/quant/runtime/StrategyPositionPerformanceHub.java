package org.dromara.quant.runtime;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** 按交易账户分发已持久化的策略持仓峰值变化。 */
@Component
public class StrategyPositionPerformanceHub {
    private final Map<Long, CopyOnWriteArrayList<Consumer<StrategyPositionPerformanceEvent>>> listeners = new ConcurrentHashMap<>();

    /** 订阅账户持仓峰值事件，并返回取消订阅句柄。 */
    public AutoCloseable subscribe(Long credentialId, Consumer<StrategyPositionPerformanceEvent> listener) {
        CopyOnWriteArrayList<Consumer<StrategyPositionPerformanceEvent>> current = listeners.computeIfAbsent(credentialId, ignored -> new CopyOnWriteArrayList<>());
        current.add(listener);
        return () -> {
            current.remove(listener);
            if (current.isEmpty()) {
                listeners.remove(credentialId, current);
            }
        };
    }

    /** 发布一条已落库的持仓峰值。 */
    public void publish(StrategyPositionPerformanceEvent event) {
        if (event == null || event.credentialId() == null) {
            return;
        }
        for (Consumer<StrategyPositionPerformanceEvent> listener : listeners.getOrDefault(event.credentialId(), new CopyOnWriteArrayList<>())) {
            try {
                listener.accept(event);
            } catch (RuntimeException ignored) {
                // 单个终端连接失败不能影响策略行情消费。
            }
        }
    }
}
