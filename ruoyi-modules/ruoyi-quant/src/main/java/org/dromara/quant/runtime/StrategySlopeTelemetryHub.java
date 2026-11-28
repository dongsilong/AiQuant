package org.dromara.quant.runtime;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Component
public class StrategySlopeTelemetryHub {
    private final Map<Key, Map<String, StrategySlopeTelemetry>> latest = new ConcurrentHashMap<>();
    private final Map<Key, Map<String, Long>> activeRuntimeInstances = new ConcurrentHashMap<>();
    private final Map<Key, CopyOnWriteArrayList<Consumer<StrategySlopeTelemetry>>> listeners = new ConcurrentHashMap<>();

    /**
     * 标记 Runtime 对该策略/账户/交易对/周期的遥测所有权，并清除此前的只读监控值。
     */
    public void registerRuntime(Long strategyId, Long credentialId, String symbol, String interval, Long instanceId) {
        if (strategyId == null || credentialId == null || symbol == null || interval == null || instanceId == null) {
            return;
        }
        Key key = new Key(strategyId, credentialId);
        String snapshotKey = snapshotKey(symbol, interval);
        Map<String, Long> runtimes = activeRuntimeInstances.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>());
        Long previousInstanceId = runtimes.put(snapshotKey, instanceId);
        if (Objects.equals(previousInstanceId, instanceId)) {
            return;
        }
        Map<String, StrategySlopeTelemetry> bySymbol = latest.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>());
        StrategySlopeTelemetry previous = bySymbol.get(snapshotKey);
        StrategySlopeTelemetry waiting = placeholder(strategyId, credentialId, symbol, interval, instanceId,
            previous == null ? null : previous.entryThreshold(),
            previous == null ? 1L : previous.sequence() + 1L, "RUNTIME", "WAITING_FOR_RUNTIME_EVENT", Instant.now());
        bySymbol.put(snapshotKey, waiting);
        notifyListeners(key, waiting);
    }

    /**
     * 释放 Runtime 遥测所有权；旧实例关闭时不能清除新实例的通道。
     */
    public void unregisterRuntime(Long strategyId, Long credentialId, String symbol, String interval, Long instanceId) {
        if (strategyId == null || credentialId == null || symbol == null || interval == null || instanceId == null) {
            return;
        }
        Key key = new Key(strategyId, credentialId);
        String snapshotKey = snapshotKey(symbol, interval);
        Map<String, Long> runtimes = activeRuntimeInstances.get(key);
        if (runtimes == null || !runtimes.remove(snapshotKey, instanceId)) {
            return;
        }
        if (runtimes.isEmpty()) {
            activeRuntimeInstances.remove(key, runtimes);
        }
        Map<String, StrategySlopeTelemetry> bySymbol = latest.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>());
        StrategySlopeTelemetry previous = bySymbol.get(snapshotKey);
        if (previous != null && "RUNTIME".equals(source(previous)) && Objects.equals(previous.instanceId(), instanceId)) {
            StrategySlopeTelemetry waiting = placeholder(strategyId, credentialId, symbol, interval, null,
                previous.entryThreshold(), previous.sequence() + 1L, "MONITOR", "MONITOR_WAITING_FOR_EVENT", Instant.now());
            bySymbol.put(snapshotKey, waiting);
            notifyListeners(key, waiting);
        }
    }

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
     * Runtime 遥测优先于只读 Monitor；仅在 Runtime 生命周期结束后允许 Monitor 接管。
     */
    public void publish(StrategySlopeTelemetry telemetry) {
        Key key = new Key(telemetry.strategyId(), telemetry.credentialId());
        String snapshotKey = snapshotKey(telemetry.symbol(), telemetry.interval());
        String dataSource = source(telemetry);
        Map<String, Long> runtimes = activeRuntimeInstances.get(key);
        if ("RUNTIME".equals(dataSource)) {
            if (telemetry.instanceId() == null) {
                return;
            }
            if (runtimes == null) {
                runtimes = activeRuntimeInstances.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>());
            }
            Long owner = runtimes.putIfAbsent(snapshotKey, telemetry.instanceId());
            if (owner != null && !Objects.equals(owner, telemetry.instanceId())) {
                return;
            }
        } else if (runtimes != null && runtimes.containsKey(snapshotKey)) {
            return;
        }

        Map<String, StrategySlopeTelemetry> bySymbol = latest.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>());
        AtomicBoolean accepted = new AtomicBoolean();
        bySymbol.compute(snapshotKey, (ignored, previous) -> {
            Map<String, Long> currentRuntimes = activeRuntimeInstances.get(key);
            if ("MONITOR".equals(dataSource) && currentRuntimes != null && currentRuntimes.containsKey(snapshotKey)) {
                return previous;
            }
            if ("RUNTIME".equals(dataSource) && currentRuntimes != null
                && !Objects.equals(currentRuntimes.get(snapshotKey), telemetry.instanceId())) {
                return previous;
            }
            boolean waitingPlaceholder = previous != null && previous.slopeBlockReason() != null
                && (previous.slopeBlockReason().endsWith("WAITING_FOR_EVENT")
                || "WAITING_FOR_MARKET".equals(previous.slopeBlockReason()));
            if (!waitingPlaceholder && previous != null && Objects.equals(previous.instanceId(), telemetry.instanceId())
                && Objects.equals(source(previous), dataSource) && previous.sequence() >= telemetry.sequence()) {
                return previous;
            }
            accepted.set(true);
            return telemetry;
        });
        if (accepted.get()) {
            notifyListeners(key, telemetry);
        }
    }

    private void notifyListeners(Key key, StrategySlopeTelemetry telemetry) {
        for (Consumer<StrategySlopeTelemetry> listener : listeners.getOrDefault(key, new CopyOnWriteArrayList<>())) {
            try {
                listener.accept(telemetry);
            } catch (RuntimeException ignored) {
                // 单个 SSE 客户端异常不能影响策略运行时。
            }
        }
    }

    private static String source(StrategySlopeTelemetry telemetry) {
        if ("RUNTIME".equalsIgnoreCase(telemetry.dataSource()) || "MONITOR".equalsIgnoreCase(telemetry.dataSource())) {
            return telemetry.dataSource().toUpperCase(java.util.Locale.ROOT);
        }
        return telemetry.instanceId() == null ? "MONITOR" : "RUNTIME";
    }

    private static String snapshotKey(String symbol, String interval) {
        return symbol.toUpperCase(java.util.Locale.ROOT) + "|" + interval;
    }

    private static StrategySlopeTelemetry placeholder(Long strategyId, Long credentialId, String symbol, String interval,
                                                       Long instanceId, java.math.BigDecimal threshold, long sequence,
                                                       String source, String reason, Instant now) {
        return new StrategySlopeTelemetry(
            strategyId, instanceId, credentialId, symbol, interval,
            null, null, null, null, threshold, false, null, null, sequence, false,
            null, null, null, false, "WAITING_FOR_MARKET", null, null, null,
            0L, 0L, now, null, null, null, null, null, null, 0L, null, source, reason
        );
    }

    private static StrategySlopeTelemetry forReplay(StrategySlopeTelemetry snapshot, Instant now) {
        long elapsedMillis = snapshot.publishedAt() == null
            ? Long.MAX_VALUE : Math.max(0L, Duration.between(snapshot.publishedAt(), now).toMillis());
        long slopeFreshForMillis = Math.max(0L, snapshot.slopeFreshForMillis() - elapsedMillis);
        long maFreshForMillis = Math.max(0L, snapshot.maFreshForMillis() - elapsedMillis);
        long displayFreshForMillis = Math.max(0L, snapshot.displayFreshForMillis() - elapsedMillis);
        boolean slopeReady = snapshot.slopeReady() && slopeFreshForMillis > 0;
        boolean maReady = snapshot.maReady() && maFreshForMillis > 0;
        String slopeBlockReason = slopeReady ? snapshot.slopeBlockReason()
            : snapshot.slopeBlockReason() == null ? "STALE_EVENT" : snapshot.slopeBlockReason();
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
            now,
            displayFreshForMillis > 0 ? snapshot.displayCurrentPrice() : null,
            displayFreshForMillis > 0 ? snapshot.displayReferenceOpenPrice() : null,
            displayFreshForMillis > 0 ? snapshot.displaySlopePercent() : null,
            displayFreshForMillis > 0 ? snapshot.displayDegrees() : null,
            snapshot.displayEventTime(),
            snapshot.displayReceivedAt(),
            Math.max(0L, snapshot.displayFreshForMillis() - elapsedMillis),
            snapshot.displaySource(),
            source(snapshot),
            slopeBlockReason
        );
    }

    private record Key(Long strategyId, Long credentialId) {
    }
}
