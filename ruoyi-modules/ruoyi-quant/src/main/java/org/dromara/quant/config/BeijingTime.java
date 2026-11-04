package org.dromara.quant.config;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * UI-facing timestamp conversion only. Never use this for Binance request signing.
 */
public final class BeijingTime {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssXXX").withZone(ZONE);
    private static final DateTimeFormatter DISPLAY_ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX").withZone(ZONE);

    private BeijingTime() {
    }

    /**
     * Include +08:00 to eliminate client-local timezone guessing.
     */
    public static String format(Instant instant) {
        return instant == null ? null : DISPLAY.format(instant);
    }

    /** Format a UTC LocalDateTime as an ISO-8601 Beijing timestamp with an explicit offset. */
    public static String formatUtc(LocalDateTime utc) {
        return utc == null ? null : DISPLAY_ISO.format(utc.toInstant(ZoneOffset.UTC));
    }

    /** Convert a Beijing wall-clock date/time to the UTC LocalDateTime used by database queries. */
    public static LocalDateTime utcFromBeijing(LocalDateTime beijing) {
        return beijing == null ? null : LocalDateTime.ofInstant(beijing.atZone(ZONE).toInstant(), ZoneOffset.UTC);
    }

    /** Convert the start of a Beijing calendar day to the UTC LocalDateTime used by database queries. */
    public static LocalDateTime utcStartOfBeijingDate(LocalDate date) {
        return LocalDateTime.ofInstant(date.atStartOfDay(ZONE).toInstant(), ZoneOffset.UTC);
    }

    public static String formatIso(Instant instant) {
        return instant == null ? null : DISPLAY_ISO.format(instant);
    }
}
