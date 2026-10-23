-- AiQuant LIVE schema contract (read-only). Run against the intended LIVE database.
-- Outputs MUST all be OK before enabling quant.live.enabled and quant.runtime.enabled.
-- This is NOT a CREATE TABLE migration. Obtain and review canonical baseline DDL before provisioning.
SELECT DATABASE() AS inspected_database;

WITH required_tables AS (
    SELECT 'quant_credential' AS table_name UNION ALL
    SELECT 'quant_strategy' UNION ALL
    SELECT 'quant_strategy_instance' UNION ALL
    SELECT 'quant_strategy_position_state' UNION ALL
    SELECT 'quant_strategy_runtime_lease' UNION ALL
    SELECT 'quant_strategy_entry_guard' UNION ALL
    SELECT 'quant_execution_record' UNION ALL
    SELECT 'quant_open_capacity' UNION ALL
    SELECT 'quant_order' UNION ALL
    SELECT 'quant_position' UNION ALL
    SELECT 'quant_protection_order' UNION ALL
    SELECT 'quant_protection_target' UNION ALL
    SELECT 'quant_trade' UNION ALL
    SELECT 'quant_trade_decision_log' UNION ALL
    SELECT 'quant_live_dispatch_guard' UNION ALL
    SELECT 'quant_order_dispatch_ticket' UNION ALL
    SELECT 'quant_strategy_fill_allocation' UNION ALL
    SELECT 'quant_live_account_settings_intent' UNION ALL
    SELECT 'quant_strategy_pre_dispatch_intent' UNION ALL
    SELECT 'quant_strategy_order_intent' UNION ALL
    SELECT 'quant_live_user_stream_lease'
)
SELECT r.table_name, COALESCE(t.engine,'ABSENT') AS actual_engine,
       CASE WHEN t.table_name IS NULL THEN 'MISSING'
            WHEN t.engine <> 'InnoDB' THEN 'WRONG_ENGINE'
            ELSE 'OK' END AS status
FROM required_tables r
LEFT JOIN information_schema.tables t
  ON t.table_schema=DATABASE() AND t.table_name=r.table_name
ORDER BY r.table_name;

-- The exact left-to-right columns are part of the concurrency/idempotency protocol.
-- Names may differ, but uniqueness MUST be enforced by the database.
WITH required_indexes AS (
  SELECT 'quant_strategy_runtime_lease' AS table_name,
         'credential_id,symbol' AS required_columns UNION ALL
  SELECT 'quant_strategy_entry_guard','credential_id,symbol,kline_open_time' UNION ALL
  SELECT 'quant_execution_record','credential_id,idempotency_key' UNION ALL
  SELECT 'quant_open_capacity','credential_id,idempotency_key' UNION ALL
  SELECT 'quant_strategy_position_state','instance_id' UNION ALL
  SELECT 'quant_protection_target','target_key' UNION ALL
  SELECT 'quant_live_dispatch_guard','credential_id,symbol' UNION ALL
  SELECT 'quant_order_dispatch_ticket','credential_id,idempotency_key' UNION ALL
  SELECT 'quant_strategy_fill_allocation','credential_id,symbol,exchange_trade_id' UNION ALL
  SELECT 'quant_live_account_settings_intent','credential_id,symbol' UNION ALL
  SELECT 'quant_strategy_pre_dispatch_intent','credential_id,idempotency_key' UNION ALL
  SELECT 'quant_strategy_order_intent','credential_id,symbol,client_order_id'
), actual_indexes AS (
  SELECT table_name, index_name, non_unique,
         GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') AS indexed_columns
  FROM information_schema.statistics
  WHERE table_schema=DATABASE()
  GROUP BY table_name,index_name,non_unique
)
SELECT r.table_name, r.required_columns,
       COALESCE(a.index_name,'MISSING') AS matching_unique_index,
       CASE WHEN a.index_name IS NULL THEN 'MISSING_UNIQUE_INDEX' ELSE 'OK' END AS status
FROM required_indexes r
LEFT JOIN actual_indexes a
  ON a.table_name=r.table_name AND a.indexed_columns=r.required_columns AND a.non_unique=0
ORDER BY r.table_name;

-- Per-strategy-symbol runtime toggle must exist before deploying the corresponding entity/runtime code.
SELECT CASE WHEN COUNT(*)=1 THEN 'OK' ELSE 'MISSING_STRATEGY_SYMBOL_RUNTIME_ENABLED' END AS status
FROM information_schema.columns
WHERE table_schema=DATABASE() AND table_name='quant_strategy_symbol'
  AND column_name='runtime_enabled' AND data_type='tinyint' AND is_nullable='NO';

-- Preflight rejects empty production credentials before any LIVE enablement.
-- Index name/shape validation is independent of H2 tests, which do not test the real production schema.

-- Fencing schema gate: the migration V20261014_01__runtime_lease_epoch.sql
-- MUST be installed before the new LIVE runtime is started.
SELECT CASE WHEN COUNT(*)=1 THEN 'OK' ELSE 'MISSING_LEASE_EPOCH' END AS status
FROM information_schema.columns
WHERE table_schema=DATABASE() AND table_name='quant_strategy_runtime_lease'
  AND column_name='lease_epoch' AND data_type='bigint' AND is_nullable='NO';
