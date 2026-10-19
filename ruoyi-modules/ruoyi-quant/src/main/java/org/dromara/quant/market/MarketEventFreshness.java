package org.dromara.quant.market;

import java.time.Instant;

/**
 * 用交易所时钟核实行情事件年龄，并用本机时钟单独检查事件进入本地队列后的延迟。
 */
public final class MarketEventFreshness {
    private MarketEventFreshness() {
    }

    /**
     * 判断交易所事件和本地接收时间是否都在允许的新鲜度窗口内。
     */
    public static boolean isFresh(Instant eventTime, Instant receivedAt, Instant localNow, Instant exchangeNow, long maxAgeMillis) {
        if (eventTime == null || receivedAt == null || localNow == null || exchangeNow == null) {
            return false;
        }
        long maxAge = Math.max(1, maxAgeMillis);
        return isExchangeTimeFresh(eventTime, exchangeNow, maxAge) && !receivedAt.isBefore(localNow.minusMillis(maxAge));
    }

    /**
     * 判断交易所事件时间是否在窗口内；允许最多一秒的服务端时间正偏差。
     */
    public static boolean isExchangeTimeFresh(Instant eventTime, Instant exchangeNow, long maxAgeMillis) {
        if (eventTime == null || exchangeNow == null) {
            return false;
        }
        long maxAge = Math.max(1, maxAgeMillis);
        return !eventTime.isBefore(exchangeNow.minusMillis(maxAge)) && !eventTime.isAfter(exchangeNow.plusSeconds(1));
    }
}
