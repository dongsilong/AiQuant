-- Distinguish event-triggered close snapshots from the database capture time.
ALTER TABLE quant_trade_close_equity
    ADD COLUMN capture_source VARCHAR(32) NOT NULL DEFAULT 'REST_FALLBACK' COMMENT '资金快照采集来源' AFTER unrealized_pnl,
    ADD COLUMN close_event_time DATETIME(3) NULL COMMENT 'Binance 最后一笔平仓成交时间（UTC）' AFTER capture_source,
    ADD COLUMN exchange_event_time DATETIME(3) NULL COMMENT '匹配的 Binance ACCOUNT_UPDATE 事件时间（UTC）' AFTER close_event_time,
    ADD COLUMN stream_received_at DATETIME(3) NULL COMMENT '应用接收对应 ACCOUNT_UPDATE 事件的时间（UTC）' AFTER exchange_event_time;
