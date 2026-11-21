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
            assertEquals(second, replayed.getFirst());
        }
    }

    @Test
    void ignoresOutOfOrderSnapshotsForTheSameRuntime() {
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
    void replayExpiresOldSlopeAndMaValuesInsteadOfRefreshingTheirAge() {
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

    private static StrategySlopeTelemetry telemetry(long sequence, BigDecimal price, Instant eventTime, Instant receivedAt,
                                                      boolean ready, String trend) {
        return new StrategySlopeTelemetry(
            2L, 1L, 3L, "BTCUSDT", "15m", price, new BigDecimal("99"),
            BigDecimal.ONE, new BigDecimal("71"), new BigDecimal("60"), true,
            eventTime, receivedAt, sequence, true,
            new BigDecimal("101.5"), new BigDecimal("101.2"), trend, ready,
            null, Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MINUTES).minusSeconds(60),
            eventTime, receivedAt, 3_000L, 3_000L, Instant.now()
        );
    }
}
