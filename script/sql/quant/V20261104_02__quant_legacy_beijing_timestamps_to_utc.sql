-- V20261104_02: normalize legacy Beijing-local DATETIME fields to UTC.
-- Apply once after a database backup and after V20261104_01 (when that migration is needed).
--
-- The listed fields were written by code paths that used Asia/Shanghai, JVM-local time
-- under the deployed Asia/Shanghai container TZ, or MySQL NOW() while MySQL SYSTEM was UTC+8.
-- The production sample confirms that MySQL SYSTEM time is UTC+8 and financial-event
-- created_at used the legacy Beijing-local representation.
--
-- Binance event facts are deliberately NOT changed:
--   quant_trade.trade_time
--   quant_order.updated_at
--   quant_financial_event.event_time
-- Strategy-cycle open/close times derived from quant_trade are also left unchanged.
--
-- quant_order.created_at is deliberately NOT changed: production samples show mixed
-- UTC/Beijing semantics, so a blanket eight-hour shift would corrupt some rows.

CREATE TABLE IF NOT EXISTS quant_time_migration_history (
    migration_key varchar(100) NOT NULL,
    applied_at datetime(3) NOT NULL,
    PRIMARY KEY (migration_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
COMMENT='量化时间语义迁移执行记录';

DELIMITER $

DROP PROCEDURE IF EXISTS migrate_quant_legacy_beijing_timestamps$

CREATE PROCEDURE migrate_quant_legacy_beijing_timestamps()
BEGIN
    DECLARE finished boolean DEFAULT FALSE;
    DECLARE target_table varchar(128);
    DECLARE target_column varchar(128);

    DECLARE targets CURSOR FOR
        SELECT 'quant_account_snapshot', 'observed_at'
        UNION ALL SELECT 'quant_financial_event', 'created_at'
        UNION ALL SELECT 'quant_trade_decision_log', 'event_time'
        UNION ALL SELECT 'quant_trade_decision_log', 'trade_time'
        UNION ALL SELECT 'quant_trade_decision_log', 'kline_time'
        UNION ALL SELECT 'quant_trade_decision_log', 'last_time'
        UNION ALL SELECT 'quant_execution_record', 'created_at'
        UNION ALL SELECT 'quant_execution_record', 'updated_at'
        UNION ALL SELECT 'quant_execution_record', 'last_recovery_at'
        UNION ALL SELECT 'quant_reconcile_record', 'reconciled_at'
        UNION ALL SELECT 'quant_position', 'updated_at'
        UNION ALL SELECT 'quant_strategy_instance', 'last_signal_at'
        UNION ALL SELECT 'quant_strategy_position_state', 'updated_at'
        UNION ALL SELECT 'quant_notification', 'created_at'
        UNION ALL SELECT 'quant_notification', 'next_retry_at'
        UNION ALL SELECT 'quant_notification', 'lease_until'
        UNION ALL SELECT 'quant_notification', 'sent_at'
        UNION ALL SELECT 'quant_strategy_runtime_lease', 'lease_until'
        UNION ALL SELECT 'quant_strategy_runtime_lease', 'create_time'
        UNION ALL SELECT 'quant_strategy_runtime_lease', 'update_time'
        UNION ALL SELECT 'quant_live_user_stream_lease', 'lease_until'
        UNION ALL SELECT 'quant_live_dispatch_guard', 'updated_at'
        UNION ALL SELECT 'quant_live_account_settings_intent', 'created_at'
        UNION ALL SELECT 'quant_order_dispatch_ticket', 'created_at'
        UNION ALL SELECT 'quant_order_dispatch_ticket', 'updated_at'
        UNION ALL SELECT 'quant_strategy_pre_dispatch_intent', 'created_at'
        UNION ALL SELECT 'quant_strategy_order_intent', 'created_at'
        UNION ALL SELECT 'quant_strategy_entry_guard', 'created_at'
        UNION ALL SELECT 'quant_strategy_fill_journal', 'created_at'
        UNION ALL SELECT 'quant_strategy_fill_journal', 'updated_at'
        UNION ALL SELECT 'quant_strategy_fill_journal', 'last_attempt_at'
        UNION ALL SELECT 'quant_strategy_fill_sync_cursor', 'updated_at'
        UNION ALL SELECT 'quant_financial_event_sync_cursor', 'updated_at'
        UNION ALL SELECT 'quant_strategy_fill_allocation', 'created_at'
        UNION ALL SELECT 'quant_strategy_position_projection', 'created_at'
        UNION ALL SELECT 'quant_strategy_position_projection', 'updated_at'
        UNION ALL SELECT 'quant_position_sync_fence', 'updated_at'
        UNION ALL SELECT 'quant_live_recovery_approval', 'created_at'
        UNION ALL SELECT 'quant_live_recovery_approval', 'reviewed_at'
        UNION ALL SELECT 'quant_live_recovery_approval', 'completed_at'
        UNION ALL SELECT 'quant_live_recovery_audit', 'created_at'
        UNION ALL SELECT 'quant_paper_account', 'updated_at';

    DECLARE CONTINUE HANDLER FOR NOT FOUND SET finished = TRUE;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        RESIGNAL;
    END;

    IF EXISTS (
        SELECT 1 FROM quant_time_migration_history
        WHERE migration_key = 'V20261104_02__quant_legacy_beijing_timestamps_to_utc'
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'V20261104_02 already applied; refusing to subtract eight hours twice';
    END IF;

    START TRANSACTION;
    OPEN targets;

    migrate_loop: LOOP
        FETCH targets INTO target_table, target_column;
        IF finished THEN
            LEAVE migrate_loop;
        END IF;

        IF EXISTS (
            SELECT 1
            FROM information_schema.columns
            WHERE table_schema = DATABASE()
              AND table_name = target_table
              AND column_name = target_column
              AND data_type = 'datetime'
        ) THEN
            SET @quant_time_sql = CONCAT(
                'UPDATE `', target_table, '` ',
                'SET `', target_column, '` = DATE_SUB(`', target_column, '`, INTERVAL 8 HOUR) ',
                'WHERE `', target_column, '` IS NOT NULL'
            );
            PREPARE quant_time_stmt FROM @quant_time_sql;
            EXECUTE quant_time_stmt;
            DEALLOCATE PREPARE quant_time_stmt;
        END IF;
    END LOOP;

    CLOSE targets;

    INSERT INTO quant_time_migration_history (migration_key, applied_at)
    VALUES ('V20261104_02__quant_legacy_beijing_timestamps_to_utc', UTC_TIMESTAMP(3));

    COMMIT;
END$

CALL migrate_quant_legacy_beijing_timestamps()$
DROP PROCEDURE migrate_quant_legacy_beijing_timestamps$

DELIMITER ;
