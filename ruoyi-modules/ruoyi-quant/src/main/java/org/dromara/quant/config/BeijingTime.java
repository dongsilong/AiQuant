package org.dromara.quant.config;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * UI-facing timestamp conversion only. Never use this for Binance request signing.
 */
public final class BeijingTime {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssXXX").withZone(ZONE);

    private BeijingTime() {
    }

    /**
     * Include +08:00 to eliminate client-local timezone guessing.
     */
    public static String format(Instant instant) {
        return instant == null ? null : DISPLAY.format(instant);
    }
}
