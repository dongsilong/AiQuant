package org.dromara.quant.account.mapper;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class QuantTradeTimeMapperTest {

    @Test
    void convertsUtcLocalDateTimeToOffsetDateTime() {
        LocalDateTime value = LocalDateTime.of(2026, 11, 20, 14, 15, 16);

        assertEquals(OffsetDateTime.of(value, ZoneOffset.UTC), QuantTradeTimeMapper.toOffsetDateTime(value));
    }

    @Test
    void convertsOffsetDateTimeBackToUtcLocalDateTimeWithoutChangingTheInstant() {
        OffsetDateTime value = OffsetDateTime.parse("2026-11-20T22:15:16+08:00");

        assertEquals(LocalDateTime.of(2026, 11, 20, 14, 15, 16), QuantTradeTimeMapper.toLocalDateTime(value));
    }

    @Test
    void keepsNullValuesNull() {
        assertNull(QuantTradeTimeMapper.toOffsetDateTime(null));
        assertNull(QuantTradeTimeMapper.toLocalDateTime(null));
    }
}
