-- AiQuant / MySQL 8.4: one-time additive migration for an EXISTING verified
-- quant_strategy_runtime_lease table. DO NOT RUN automatically on an unknown schema.
--
-- REQUIRED preconditions:
-- 1. Disable LIVE and all strategy runtimes on EVERY application node.
-- 2. Confirm no outbound requests are in flight. Back up the table/database.
-- 3. SHOW CREATE TABLE quant_strategy_runtime_lease; must contain a unique key
--    on (credential_id,symbol) and the existing owner_token / lease_until columns.
-- 4. Check information_schema.columns that lease_epoch is ABSENT. If present,
--    do not reapply; investigate version history instead.
-- 5. This ALTER is not fully transaction-rollbackable; test on a cloned database.
--
-- Existing rows start at epoch 1; fresh rows also start at 1. Future takeover
-- increments this value monotonically; releasing a lease never deletes its row.
ALTER TABLE quant_strategy_runtime_lease
  ADD COLUMN lease_epoch BIGINT NOT NULL DEFAULT 1
  COMMENT 'Monotonic owner epoch; increment on lease takeover or expiry';

-- Validate no null/zero epoch, and inspect the unique key after ALTER.
SELECT COUNT(*) AS invalid_lease_epochs
FROM quant_strategy_runtime_lease
WHERE lease_epoch IS NULL OR lease_epoch < 1;

-- Deploy ONLY the new lease mapper/strategy execution code to all nodes before
-- enabling LIVE. Old versions DELETE lease rows and would reset epochs.
-- This is not a complete cross-node order-dispatch fence or a full schema baseline.
