-- Coordinate manual and periodic Binance fill-history collection per credential and symbol.
-- Apply after taking a database backup.
CREATE TABLE IF NOT EXISTS quant_trade_history_collection_lease (
    credential_id BIGINT NOT NULL COMMENT '量化凭证ID',
    symbol VARCHAR(32) NOT NULL COMMENT '交易对',
    owner_token VARCHAR(36) NOT NULL DEFAULT '' COMMENT '当前采集节点持有者令牌',
    lease_epoch BIGINT NOT NULL DEFAULT 0 COMMENT '租约隔离代次',
    lease_until DATETIME(6) NOT NULL COMMENT '租约到期时间，使用数据库时钟',
    PRIMARY KEY (credential_id, symbol),
    KEY idx_quant_trade_history_collection_lease_until (lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='量化账户交易对成交历史采集租约';
