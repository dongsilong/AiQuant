-- Unknown commission asset is a valid incomplete exchange fact and must remain NULL.
-- Apply after taking a database backup.
ALTER TABLE quant_strategy_fill_journal
    MODIFY COLUMN commission_asset VARCHAR(32)
        CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL;
