-- Persist the one-time account-level recovery state for an emptied quant_trade ledger.
-- Apply after taking a database backup.
CREATE TABLE IF NOT EXISTS quant_trade_ledger_recovery (
    credential_id BIGINT NOT NULL COMMENT '量化凭证ID',
    status VARCHAR(16) NOT NULL DEFAULT 'READY' COMMENT '恢复状态：READY/RUNNING/COMPLETED',
    lease_until DATETIME(3) NOT NULL COMMENT '恢复任务租约到期时间',
    updated_at DATETIME(3) NOT NULL COMMENT '状态更新时间',
    PRIMARY KEY (credential_id),
    KEY idx_quant_trade_ledger_recovery_lease (status, lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='量化成交账本订单驱动恢复状态';
