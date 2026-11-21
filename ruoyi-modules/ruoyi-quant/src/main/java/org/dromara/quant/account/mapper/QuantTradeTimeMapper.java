package org.dromara.quant.account.mapper;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 成交账本时间与接口时间类型之间的映射。
 * quant_trade.trade_time 在数据库中按 UTC 保存。
 */
public final class QuantTradeTimeMapper {

    private QuantTradeTimeMapper() {
    }

    /**
     * 将数据库中的 UTC LocalDateTime 转换为带 UTC 偏移量的接口时间。
     */
    public static OffsetDateTime toOffsetDateTime(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    /**
     * 将带偏移量的接口时间转换为数据库使用的 UTC LocalDateTime。
     */
    public static LocalDateTime toLocalDateTime(OffsetDateTime value) {
        return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }
}
