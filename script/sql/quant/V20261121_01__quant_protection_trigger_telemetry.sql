-- Record Binance Algo trigger timing and the nearest pre-trigger market-price samples.
-- Apply after taking a database backup.
ALTER TABLE quant_protection_order
    ADD COLUMN trigger_time_epoch_ms BIGINT NULL COMMENT 'Binance Algo 实际触发时间，Unix epoch 毫秒',
    ADD COLUMN trigger_mark_price DECIMAL(30, 12) NULL COMMENT '触发时间前最近有效 MARK_PRICE 样本',
    ADD COLUMN trigger_mark_price_at_epoch_ms BIGINT NULL COMMENT 'MARK_PRICE 样本时间，Unix epoch 毫秒',
    ADD COLUMN trigger_contract_trade_price DECIMAL(30, 12) NULL COMMENT '触发时间前最近有效 aggTrade 合约成交价样本',
    ADD COLUMN trigger_contract_trade_at_epoch_ms BIGINT NULL COMMENT '合约成交价样本时间，Unix epoch 毫秒',
    ADD COLUMN child_created_at_epoch_ms BIGINT NULL COMMENT 'Binance 实际子市价单创建时间，Unix epoch 毫秒',
    ADD COLUMN trigger_to_child_delay_ms BIGINT NULL COMMENT 'Algo 实际触发至子市价单创建的耗时',
    ADD COLUMN actual_fill_price DECIMAL(30, 12) NULL COMMENT '实际子市价单成交均价',
    ADD COLUMN adverse_slippage_pct DECIMAL(20, 8) NULL COMMENT '相对保护触发价的方向性不利滑点百分比';