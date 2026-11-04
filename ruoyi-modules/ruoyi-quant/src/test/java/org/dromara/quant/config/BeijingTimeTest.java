package org.dromara.quant.config;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@Tag("dev")
class BeijingTimeTest {
    @Test
    void convertsUtcInstantToBeijingWithoutChangingTheInstant() {
        Instant instant = Instant.parse("2026-10-14T12:00:00Z");
        assertEquals("2026-10-14 20:00:00+08:00", BeijingTime.format(instant));
        assertEquals("2026-10-14T12:00:00Z", instant.toString());
    }

    @Test
    void midnightCrossesBeijingCalendarDayAndNeverDependsOnHostZone() {
        assertEquals("2026-10-15 07:30:00+08:00", BeijingTime.format(Instant.parse("2026-10-14T23:30:00Z")));
        assertNull(BeijingTime.format(null));
    }

    @Test
    void utcDatabaseTimeIsReturnedAsExplicitBeijingIsoTimestamp() {
        assertEquals("2026-11-04T21:37:56+08:00",
            BeijingTime.formatUtc(LocalDateTime.of(2026, 11, 4, 13, 37, 56)));
    }

    @Test
    void BeijingDateAndWallClockBoundariesConvertToUtc() {
        assertEquals(LocalDateTime.of(2026, 11, 3, 16, 0),
            BeijingTime.utcStartOfBeijingDate(LocalDate.of(2026, 11, 4)));
        assertEquals(LocalDateTime.of(2026, 11, 4, 13, 37, 56),
            BeijingTime.utcFromBeijing(LocalDateTime.of(2026, 11, 4, 21, 37, 56)));
    }
}
