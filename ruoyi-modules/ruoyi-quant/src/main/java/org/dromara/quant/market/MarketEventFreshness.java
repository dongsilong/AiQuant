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
        return rejectionReason(eventTime, receivedAt, localNow, exchangeNow, maxAgeMillis) == null;
    }

    /**
     * 返回新鲜度拒绝原因；返回 null 表示通过。原因顺序与 isFresh 的校验顺序一致。
     */
    public static String rejectionReason(Instant eventTime, Instant receivedAt, Instant localNow, Instant exchangeNow, long maxAgeMillis) {
        if (eventTime == null) {
            return "EVENT_TIME_MISSING";
        }
        if (receivedAt == null) {
            return "RECEIVED_AT_MISSING";
        }
        if (localNow == null || exchangeNow == null) {
            return "CLOCK_TIME_MISSING";
        }
        long maxAge = Math.max(1, maxAgeMillis);
        if (eventTime.isBefore(exchangeNow.minusMillis(maxAge))) {
            return "EXCHANGE_EVENT_STALE";
        }
        if (eventTime.isAfter(exchangeNow.plusSeconds(1))) {
            return "EXCHANGE_TIME_FUTURE";
        }
        if (receivedAt.isBefore(localNow.minusMillis(maxAge))) {
            return "LOCAL_RECEIVE_STALE";
        }
        return null;
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
