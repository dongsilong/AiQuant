package org.dromara.quant.config;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 量化接口时间格式化。仅用于对外展示，不得用于 Binance 请求签名或交易计算。
 */
public final class BeijingTime {
    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private BeijingTime() {
    }

    /**
     * 输出带目标时区偏移量的 ISO-8601 时间，避免客户端按本地时区猜测。
     */
    public static String format(Instant instant, ZoneId zone) {
        return instant == null ? null : DISPLAY.format(instant.atZone(zone));
    }
}
