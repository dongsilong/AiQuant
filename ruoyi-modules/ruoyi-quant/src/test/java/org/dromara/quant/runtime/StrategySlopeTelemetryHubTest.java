package org.dromara.quant.runtime;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("dev")
class StrategySlopeTelemetryHubTest {
    @Test
    void testnetSlopeSnapshotsUpdateAcrossTicksAndReplayLatestToNewTerminal() throws Exception {
        var hub = new StrategySlopeTelemetryHub();
        var events = new ArrayList<StrategySlopeTelemetry>();
        var first = new StrategySlopeTelemetry(2L, 1L, 3L, "BTCUSDT", "1m", new BigDecimal("100"), new BigDecimal("99"), BigDecimal.ONE, new BigDecimal("45"), new BigDecimal("60"
        ), false, Instant.parse("2026-10-14T12:00:00Z"));
        var second = new StrategySlopeTelemetry(2L, 1L, 3L, "BTCUSDT", "1m", new BigDecimal("102"), new BigDecimal("99"), new BigDecimal("3.0303"), new BigDecimal("71"),
            new BigDecimal("60"), true, Instant.parse("2026-10-14T12:00:01Z"));
        try (var subscription = hub.subscribe(2L, 3L, events::add)) {
            hub.publish(first);
            hub.publish(second);
            assertEquals(2, events.size());
            assertEquals(new BigDecimal("102"), events.get(1).currentPrice());
            assertTrue(events.get(1).entryApproved());
        }
        var replayed = new ArrayList<StrategySlopeTelemetry>();
        try (var subscription = hub.subscribe(2L, 3L, replayed::add)) {
            assertEquals(1, replayed.size());
            assertEquals(second, replayed.getFirst());
        }
    }
}
