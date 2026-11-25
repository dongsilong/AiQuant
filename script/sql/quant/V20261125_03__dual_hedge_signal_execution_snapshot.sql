-- 双开信号必须持久化触发时的价格和事件时间，供后续恢复流程核验。
ALTER TABLE quant_strategy_hedge_round
    ADD COLUMN reference_price decimal(32,16) DEFAULT NULL COMMENT '触发信号时的15m收盘价，用于数量计算与执行参考',
    ADD COLUMN signal_event_time datetime(3) DEFAULT NULL COMMENT '触发信号的交易所事件时间，用于过期校验';

ALTER TABLE quant_strategy_hedge_round
    MODIFY COLUMN status varchar(24) NOT NULL COMMENT '轮次状态：SIGNAL_READY/OPENING/DEGRADED/OPEN/CLOSING/CLOSED/ABORTED';
