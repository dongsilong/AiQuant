-- Persist long-running manual order/trade synchronization tasks.
-- The generated unique key allows only one PENDING/RUNNING task per credential.
CREATE TABLE IF NOT EXISTS `quant_trading_sync_task` (
    `id` BIGINT NOT NULL,
    `credential_id` BIGINT NOT NULL,
    `requested_symbol` VARCHAR(32) NULL,
    `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    `total_symbol_count` INT NOT NULL DEFAULT 0,
    `completed_symbol_count` INT NOT NULL DEFAULT 0,
    `order_count` INT NOT NULL DEFAULT 0,
    `trade_count` INT NOT NULL DEFAULT 0,
    `current_symbol` VARCHAR(32) NULL,
    `error_message` VARCHAR(1000) NULL,
    `created_at` DATETIME(3) NOT NULL,
    `started_at` DATETIME(3) NULL,
    `updated_at` DATETIME(3) NOT NULL,
    `completed_at` DATETIME(3) NULL,
    `worker_token` VARCHAR(64) NULL,
    `lease_until` DATETIME(3) NULL,
    `active_credential_id` BIGINT
        GENERATED ALWAYS AS (
            CASE WHEN `status` IN ('PENDING', 'RUNNING') THEN `credential_id` ELSE NULL END
        ) STORED,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_quant_trading_sync_active_credential` (`active_credential_id`),
    KEY `idx_quant_trading_sync_credential_created` (`credential_id`, `created_at`),
    KEY `idx_quant_trading_sync_status_lease` (`status`, `lease_until`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;
