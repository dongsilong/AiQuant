-- Record the ACCOUNT_UPDATE matching-engine transaction timestamp used to correlate a close fill.
ALTER TABLE quant_trade_close_equity
    ADD COLUMN account_update_transaction_time DATETIME(3) NULL COMMENT '与平仓成交匹配的 Binance ACCOUNT_UPDATE 撮合事务时间（UTC）' AFTER close_event_time;
