package org.dromara.quant.runtime;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("dev")
class StrategySlopeTelemetryHubTest {
    @Test
    void testnetSlopeAndMaSnapshotsUpdateAcrossTicksAndReplayLatestToNewTerminal() throws Exception {
        var hub = new StrategySlopeTelemetryHub();
        var events = new ArrayList<StrategySlopeTelemetry>();
        Instant now = Instant.now();
        var first = telemetry(1L, new BigDecimal("100"), now.minusSeconds(1), now, true, "UP");
        var second = telemetry(2L, new BigDecimal("102"), now.minusMillis(200), now, true, "DOWN");
        try (var subscription = hub.subscribe(2L, 3L, events::add)) {
            hub.publish(first);
            hub.publish(second);
            assertEquals(2, events.size());
            assertEquals(new BigDecimal("102"), events.get(1).currentPrice());
            assertTrue(events.get(1).slopeReady());
            assertTrue(events.get(1).maReady());
            assertEquals("DOWN", events.get(1).maTrend());
        }
        var replayed = new ArrayList<StrategySlopeTelemetry>();
        try (var subscription = hub.subscribe(2L, 3L, replayed::add)) {
            assertEquals(1, replayed.size());
            assertEquals(second.sequence(), replayed.getFirst().sequence());
            assertEquals(second.currentPrice(), replayed.getFirst().currentPrice());
            assertTrue(replayed.getFirst().maReady());
        }
    }

    @Test
    void ignoresOutOfOrderSnapshotsForTheSameRuntime() throws Exception {
        var hub = new StrategySlopeTelemetryHub();
        var events = new ArrayList<StrategySlopeTelemetry>();
        try (var subscription = hub.subscribe(2L, 3L, events::add)) {
            hub.publish(telemetry(2L, new BigDecimal("102"), Instant.now(), Instant.now(), true, "UP"));
            hub.publish(telemetry(1L, new BigDecimal("100"), Instant.now(), Instant.now(), true, "DOWN"));
            assertEquals(1, events.size());
            assertEquals(new BigDecimal("102"), events.getFirst().currentPrice());
        }
    }

    @Test
    void registeredRuntimeBlocksReadOnlyMonitorBeforeFirstDecisionEvent() throws Exception {
        var hub = new StrategySlopeTelemetryHub();
        var events = new ArrayList<StrategySlopeTelemetry>();
        hub.registerRuntime(2L, 3L, "BTCUSDT", "15m", 42L);
        try (var subscription = hub.subscribe(2L, 3L, events::add)) {
            assertEquals(1, events.size());
            assertEquals(42L, events.getFirst().instanceId());
            assertEquals("RUNTIME", events.getFirst().dataSource());
            assertEquals("WAITING_FOR_RUNTIME_EVENT", events.getFirst().slopeBlockReason());

            var monitor = new StrategySlopeTelemetry(
                2L, null, 3L, "BTCUSDT", "15m", new BigDecimal("103"), new BigDecimal("99"),
                BigDecimal.ONE, new BigDecimal("71"), new BigDecimal("60"), true,
                Instant.now(), Instant.now(), 1L, true, null, null, null, false, "WAITING_FOR_MARKET",
                null, null, null, 3_000L, 0L, Instant.now(),
                new BigDecimal("103"), new BigDecimal("99"), BigDecimal.ONE, new BigDecimal("71"),
                Instant.now(), Instant.now(), 3_000L, "TRADE_KLINE");
            hub.publish(monitor);
            assertEquals(1, events.size());

            var runtime = copyWithSource(telemetry(1L, new BigDecimal("104"), Instant.now(), Instant.now(), true, "UP"),
                42L, 1L, Instant.now());
            hub.publish(runtime);
            assertEquals(2, events.size());
            assertEquals(42L, events.getLast().instanceId());
            assertEquals("RUNTIME", events.getLast().dataSource());
        }
    }

    @Test
    void replayExpiresOldSlopeAndMaValuesInsteadOfRefreshingTheirAge() throws Exception {
        var hub = new StrategySlopeTelemetryHub();
        var replayed = new ArrayList<StrategySlopeTelemetry>();
        var old = telemetry(7L, new BigDecimal("102"), Instant.now().minusSeconds(10), Instant.now().minusSeconds(10), true, "UP");
        hub.publish(old);
        try (var subscription = hub.subscribe(2L, 3L, replayed::add)) {
            assertEquals(1, replayed.size());
            assertFalse(replayed.getFirst().slopeReady());
            assertFalse(replayed.getFirst().maReady());
            assertNull(replayed.getFirst().ma5());
            assertEquals("STALE_EVENT", replayed.getFirst().maBlockReason());
            assertEquals(0L, replayed.getFirst().maFreshForMillis());
        }
    }

    @Test
    void freshRuntimeTelemetryTakesPriorityOverReadOnlyMonitorTelemetry() throws Exception {
        var freshHub = new StrategySlopeTelemetryHub();
        var freshEvents = new ArrayList<StrategySlopeTelemetry>();
        Instant now = Instant.now();
        var runtime = telemetry(10L, new BigDecimal("100"), now.minusMillis(100), now.minusMillis(100), true, "UP");
        var monitor = copyWithSource(runtime, null, 1L, now);
        try (var subscription = freshHub.subscribe(2L, 3L, freshEvents::add)) {
            freshHub.publish(runtime);
            freshHub.publish(monitor);
            assertEquals(1, freshEvents.size());
            assertEquals(runtime.instanceId(), freshEvents.getFirst().instanceId());
            assertEquals(runtime.currentPrice(), freshEvents.getFirst().currentPrice());
        }

        var staleHub = new StrategySlopeTelemetryHub();
        var staleEvents = new ArrayList<StrategySlopeTelemetry>();
        staleHub.publish(copyWithSource(runtime, runtime.instanceId(), runtime.sequence(), now.minusSeconds(10)));
        try (var subscription = staleHub.subscribe(2L, 3L, staleEvents::add)) {
            assertEquals(1, staleEvents.size());
            assertEquals(runtime.instanceId(), staleEvents.getFirst().instanceId());
            assertEquals("RUNTIME", staleEvents.getFirst().dataSource());
            staleHub.publish(monitor);
            assertEquals(1, staleEvents.size());

            staleHub.unregisterRuntime(2L, 3L, "BTCUSDT", "15m", runtime.instanceId());
            assertEquals(2, staleEvents.size());
            assertNull(staleEvents.getLast().instanceId());
            assertEquals("MONITOR", staleEvents.getLast().dataSource());
            staleHub.publish(monitor);
            assertEquals(3, staleEvents.size());
            assertEquals(monitor.currentPrice(), staleEvents.getLast().currentPrice());
        }
    }

    @Test
    void replayExpiresDisplaySlopeIndependentlyFromDecisionSlope() throws Exception {
        var hub = new StrategySlopeTelemetryHub();
        var replayed = new ArrayList<StrategySlopeTelemetry>();
        Instant old = Instant.now().minusSeconds(10);
        var snapshot = copyWithDisplay(telemetry(9L, new BigDecimal("100"), old, old, true, "UP"),
            new BigDecimal("103"), new BigDecimal("72"), old, 3_000L, "MARK");
        hub.publish(snapshot);
        try (var subscription = hub.subscribe(2L, 3L, replayed::add)) {
            assertEquals(1, replayed.size());
            assertTrue(replayed.getFirst().slopeReady());
            assertEquals(new BigDecimal("100"), replayed.getFirst().currentPrice());
            assertNull(replayed.getFirst().displayCurrentPrice());
            assertEquals(0L, replayed.getFirst().displayFreshForMillis());
        }
    }

    @Test
    void markDisplaySlopeRemainsSeparateFromDecisionSlopeAndRuntimeSnapshotKeepsPriority() throws Exception {
        var hub = new StrategySlopeTelemetryHub();
        var events = new ArrayList<StrategySlopeTelemetry>();
        Instant now = Instant.now();
        var runtime = copyWithDisplay(telemetry(10L, new BigDecimal("100"), now, now, true, "UP"),
            new BigDecimal("103"), new BigDecimal("2.5"), now, 3_000L, "MARK");
        var monitor = copyWithSource(runtime, null, 1L, now);
        try (var subscription = hub.subscribe(2L, 3L, events::add)) {
            hub.publish(runtime);
            hub.publish(monitor);
            assertEquals(1, events.size());
            assertEquals(runtime.instanceId(), events.getFirst().instanceId());
            assertEquals(new BigDecimal("100"), events.getFirst().currentPrice());
            assertEquals(new BigDecimal("103"), events.getFirst().displayCurrentPrice());
            assertEquals(new BigDecimal("2.5"), events.getFirst().displayDegrees());
            assertEquals("MARK", events.getFirst().displaySource());
            assertTrue(events.getFirst().slopeReady());
        }
    }

    @Test
    void keepsSnapshotsForTheSameSymbolAndDifferentIntervals() throws Exception {
        var hub = new StrategySlopeTelemetryHub();
        var first = telemetry(1L, new BigDecimal("100"), Instant.now(), Instant.now(), true, "UP");
        var second = copyWithInterval(first, "1m");
        var replayed = new ArrayList<StrategySlopeTelemetry>();
        hub.publish(first);
        hub.publish(second);
        try (var subscription = hub.subscribe(2L, 3L, replayed::add)) {
            assertEquals(2, replayed.size());
            assertTrue(replayed.stream().anyMatch(item -> "15m".equals(item.interval())));
            assertTrue(replayed.stream().anyMatch(item -> "1m".equals(item.interval())));
        }
    }

    private static StrategySlopeTelemetry copyWithDisplay(StrategySlopeTelemetry source, BigDecimal displayPrice,
                                                                BigDecimal displayDegrees, Instant receivedAt,
                                                                long freshForMillis, String displaySource) {
        return new StrategySlopeTelemetry(
            source.strategyId(), source.instanceId(), source.credentialId(), source.symbol(), source.interval(),
            source.currentPrice(), source.referenceOpenPrice(), source.slopePercent(), source.degrees(),
            source.entryThreshold(), source.entryApproved(), source.eventTime(), source.receivedAt(), source.sequence(),
            source.slopeReady(), source.ma5(), source.ma7(), source.maTrend(), source.maReady(), source.maBlockReason(),
            source.maLastClosedKlineOpenTime(), source.maEventTime(), source.maReceivedAt(), 30_000L,
            source.maFreshForMillis(), source.publishedAt(), displayPrice, source.displayReferenceOpenPrice(),
            source.displaySlopePercent(), displayDegrees, receivedAt, receivedAt, freshForMillis, displaySource
        );
    }

    private static StrategySlopeTelemetry copyWithInterval(StrategySlopeTelemetry source, String interval) {
        return new StrategySlopeTelemetry(
            source.strategyId(), source.instanceId(), source.credentialId(), source.symbol(), interval,
            source.currentPrice(), source.referenceOpenPrice(), source.slopePercent(), source.degrees(),
            source.entryThreshold(), source.entryApproved(), source.eventTime(), source.receivedAt(),
            source.sequence(), source.slopeReady(), source.ma5(), source.ma7(), source.maTrend(), source.maReady(),
            source.maBlockReason(), source.maLastClosedKlineOpenTime(), source.maEventTime(), source.maReceivedAt(),
            source.slopeFreshForMillis(), source.maFreshForMillis(), source.publishedAt(),
            source.displayCurrentPrice(), source.displayReferenceOpenPrice(), source.displaySlopePercent(),
            source.displayDegrees(), source.displayEventTime(), source.displayReceivedAt(),
            source.displayFreshForMillis(), source.displaySource()
        );
    }

    private static StrategySlopeTelemetry copyWithSource(StrategySlopeTelemetry source, Long instanceId,
                                                           long sequence, Instant publishedAt) {
        return new StrategySlopeTelemetry(
            source.strategyId(), instanceId, source.credentialId(), source.symbol(), source.interval(),
            source.currentPrice(), source.referenceOpenPrice(), source.slopePercent(), source.degrees(),
            source.entryThreshold(), source.entryApproved(), source.eventTime(), source.receivedAt(),
            sequence, source.slopeReady(), source.ma5(), source.ma7(), source.maTrend(), source.maReady(),
            source.maBlockReason(), source.maLastClosedKlineOpenTime(), source.maEventTime(), source.maReceivedAt(),
            source.slopeFreshForMillis(), source.maFreshForMillis(), publishedAt,
            source.displayCurrentPrice(), source.displayReferenceOpenPrice(), source.displaySlopePercent(),
            source.displayDegrees(), source.displayEventTime(), source.displayReceivedAt(),
            source.displayFreshForMillis(), source.displaySource()
        );
    }

    private static StrategySlopeTelemetry telemetry(long sequence, BigDecimal price, Instant eventTime, Instant receivedAt,
                                                      boolean ready, String trend) {
        return new StrategySlopeTelemetry(
            2L, 1L, 3L, "BTCUSDT", "15m", price, new BigDecimal("99"),
            BigDecimal.ONE, new BigDecimal("71"), new BigDecimal("60"), true,
            eventTime, receivedAt, sequence, true,
            new BigDecimal("101.5"), new BigDecimal("101.2"), trend, ready,
            null, Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MINUTES).minusSeconds(60),
            eventTime, receivedAt, 3_000L, 3_000L, receivedAt
        );
    }
}
