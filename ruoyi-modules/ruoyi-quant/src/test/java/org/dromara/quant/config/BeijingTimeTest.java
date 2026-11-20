package org.dromara.quant.config;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@Tag("dev")
class BeijingTimeTest {
    @Test
    void convertsUtcInstantToBeijingWithoutChangingTheInstant() {
        Instant instant = Instant.parse("2026-10-14T12:00:00Z");
        assertEquals("2026-10-14T20:00:00+08:00", BeijingTime.format(instant, ZoneId.of("Asia/Shanghai")));
        assertEquals("2026-10-14T12:00:00Z", instant.toString());
    }

    @Test
    void supportsConfiguredNonBeijingIanaZone() {
        assertEquals("2026-10-14T08:00:00-04:00",
            BeijingTime.format(Instant.parse("2026-10-14T12:00:00Z"), ZoneId.of("America/New_York")));
    }

    @Test
    void midnightCrossesBeijingCalendarDayAndNeverDependsOnHostZone() {
        assertEquals("2026-10-15T07:30:00+08:00",
            BeijingTime.format(Instant.parse("2026-10-14T23:30:00Z"), ZoneId.of("Asia/Shanghai")));
        assertNull(BeijingTime.format(null, ZoneId.of("Asia/Shanghai")));
    }
}
