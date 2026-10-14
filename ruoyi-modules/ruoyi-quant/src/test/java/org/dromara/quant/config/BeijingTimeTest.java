package org.dromara.quant.config;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

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
        assertEquals("2026-10-15 07:30:00+08:00",
            BeijingTime.format(Instant.parse("2026-10-14T23:30:00Z")));
        assertNull(BeijingTime.format(null));
    }
}
