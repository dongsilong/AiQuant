-- Distinguish ticket-backed recovery from historical TESTNET OPEN recovery without a dispatch ticket.
-- Apply after taking a database backup.
ALTER TABLE quant_live_recovery_case
    ADD COLUMN recovery_mode VARCHAR(32) NOT NULL DEFAULT 'TICKETED'
        COMMENT '恢复类型：TICKETED 普通派发票据；LEGACY_TESTNET_OPEN 历史 TESTNET 无票据开仓'
        AFTER operation_type;
