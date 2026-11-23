package org.dromara.quant.runtime;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 按策略和账户分发策略实际斜率及均线结果的进程内 SSE 事件中心。
 */
@Component
public class StrategySlopeTelemetryHub {
    private final Map<Key, Map<String, StrategySlopeTelemetry>> latest = new ConcurrentHashMap<>();
    private final Map<Key, CopyOnWriteArrayList<Consumer<StrategySlopeTelemetry>>> listeners = new ConcurrentHashMap<>();

    /**
     * 订阅指定策略和账户的遥测；回放最新快照时同步扣除已消耗的有效时长。
     */
    public AutoCloseable subscribe(Long strategyId, Long credentialId, Consumer<StrategySlopeTelemetry> listener) {
        Key key = new Key(strategyId, credentialId);
        listeners.computeIfAbsent(key, ignored -> new CopyOnWriteArrayList<>()).add(listener);
        Instant now = Instant.now();
        latest.getOrDefault(key, Map.of()).values().forEach(snapshot -> listener.accept(forReplay(snapshot, now)));
        return () -> {
            CopyOnWriteArrayList<Consumer<StrategySlopeTelemetry>> current = listeners.get(key);
            if (current != null) {
                current.remove(listener);
                if (current.isEmpty()) {
                    listeners.remove(key, current);
                }
            }
        };
    }

    /**
     * 向匹配的订阅者广播一条斜率遥测；拒绝同一 Runtime 的乱序旧快照。
     */
    public void publish(StrategySlopeTelemetry telemetry) {
        Key key = new Key(telemetry.strategyId(), telemetry.credentialId());
        Map<String, StrategySlopeTelemetry> bySymbol = latest.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>());
        AtomicBoolean accepted = new AtomicBoolean();
        String snapshotKey = telemetry.symbol() + "\u0000" + telemetry.interval();
        bySymbol.compute(snapshotKey, (ignored, previous) -> {
            if (previous != null && previous.instanceId() != null && telemetry.instanceId() == null
                && hasFreshSlope(previous, Instant.now())) {
                return previous;
            }
            if (previous != null && java.util.Objects.equals(previous.instanceId(), telemetry.instanceId())
                && previous.sequence() >= telemetry.sequence()) {
                return previous;
            }
            accepted.set(true);
            return telemetry;
        });
        if (!accepted.get()) {
            return;
        }
        for (Consumer<StrategySlopeTelemetry> listener : listeners.getOrDefault(key, new CopyOnWriteArrayList<>())) {
            try {
                listener.accept(telemetry);
            } catch (RuntimeException ignored) {
                // 单个 SSE 客户端异常不能影响策略运行时。
            }
        }
    }

    private static boolean hasFreshSlope(StrategySlopeTelemetry snapshot, Instant now) {
        if (!snapshot.slopeReady() || snapshot.publishedAt() == null) {
            return false;
        }
        long elapsedMillis = Math.max(0L, Duration.between(snapshot.publishedAt(), now).toMillis());
        return snapshot.slopeFreshForMillis() > elapsedMillis;
    }

    private static StrategySlopeTelemetry forReplay(StrategySlopeTelemetry snapshot, Instant now) {
        long elapsedMillis = snapshot.publishedAt() == null
            ? Long.MAX_VALUE : Math.max(0L, Duration.between(snapshot.publishedAt(), now).toMillis());
        long slopeFreshForMillis = Math.max(0L, snapshot.slopeFreshForMillis() - elapsedMillis);
        long maFreshForMillis = Math.max(0L, snapshot.maFreshForMillis() - elapsedMillis);
        boolean slopeReady = snapshot.slopeReady() && slopeFreshForMillis > 0;
        boolean maReady = snapshot.maReady() && maFreshForMillis > 0;
        return new StrategySlopeTelemetry(
            snapshot.strategyId(), snapshot.instanceId(), snapshot.credentialId(), snapshot.symbol(), snapshot.interval(),
            slopeReady ? snapshot.currentPrice() : null,
            slopeReady ? snapshot.referenceOpenPrice() : null,
            slopeReady ? snapshot.slopePercent() : null,
            slopeReady ? snapshot.degrees() : null,
            snapshot.entryThreshold(),
            slopeReady && snapshot.entryApproved(),
            snapshot.eventTime(),
            snapshot.receivedAt(),
            snapshot.sequence(),
            slopeReady,
            maReady ? snapshot.ma5() : null,
            maReady ? snapshot.ma7() : null,
            maReady ? snapshot.maTrend() : null,
            maReady,
            maReady ? null : (snapshot.maBlockReason() == null ? "STALE_EVENT" : snapshot.maBlockReason()),
            snapshot.maLastClosedKlineOpenTime(),
            snapshot.maEventTime(),
            snapshot.maReceivedAt(),
            slopeFreshForMillis,
            maFreshForMillis,
            now
        );
    }

    private record Key(Long strategyId, Long credentialId) {
    }
}
