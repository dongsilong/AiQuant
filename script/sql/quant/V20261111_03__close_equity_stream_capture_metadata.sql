-- Distinguish exchange order-event time from the later REST account snapshot capture time.
ALTER TABLE quant_trade_close_equity
    ADD COLUMN capture_source VARCHAR(32) NOT NULL DEFAULT 'REST_FALLBACK' COMMENT '资金快照采集来源' AFTER unrealized_pnl,
    ADD COLUMN exchange_event_time DATETIME(3) NULL COMMENT '触发本次快照的 Binance 订单事件时间（UTC）' AFTER capture_source,
    ADD COLUMN stream_received_at DATETIME(3) NULL COMMENT '应用接收 Binance 用户数据流事件的时间（UTC）' AFTER exchange_event_time;
