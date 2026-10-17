-- AiQuant CURRENT BRANCH: COMBINED ADDITIVE MIGRATION SOURCE V01–V10
-- REFERENCE / REVIEW ONLY. THIS IS NOT A FULL PRODUCTION INITIALIZATION SCHEMA.
-- Existing quant_* tables, indexes, triggers and base data are NOT included.
-- Requires independently reviewed canonical production DDL, verified backup,
-- inactive LIVE workers, preflight and a real restored-clone rehearsal.
-- Do NOT blindly execute on production. MySQL DDL is not transaction-rollbackable.
-- V01 requires existing quant_strategy_runtime_lease; V06 has mysql DELIMITER directives.

-- ===== V20261014_01__runtime_lease_epoch.sql =====
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

-- ===== V20261014_02__live_dispatch_guard.sql =====
-- AiQuant LIVE outbound order dispatcher, MySQL 8 / InnoDB.
-- One-time additive migration; review against the actual production schema first.
-- PRECONDITIONS: LIVE disabled on EVERY node; no unresolved outbound order;
-- backup/restore drill completed; V20261014_01__runtime_lease_epoch.sql installed.
-- Do NOT use CREATE TABLE IF NOT EXISTS: a mismatched existing table must fail deployment.
--
-- The guard deliberately has NO expires_at field. An UNKNOWN in-flight Binance
-- result must NOT be automatically stolen/retried after a timeout or node crash.
CREATE TABLE quant_live_dispatch_guard (
  credential_id BIGINT NOT NULL,
  symbol VARCHAR(32) NOT NULL,
  active_dispatch_key VARCHAR(160) DEFAULT NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (credential_id, symbol)
) ENGINE=InnoDB COMMENT='Single unexpired in-flight exchange dispatch per account and symbol';

CREATE TABLE quant_order_dispatch_ticket (
  id BIGINT NOT NULL AUTO_INCREMENT,
  credential_id BIGINT NOT NULL,
  symbol VARCHAR(32) NOT NULL,
  idempotency_key VARCHAR(160) NOT NULL,
  operation_type VARCHAR(16) NOT NULL,
  client_order_id VARCHAR(80) NOT NULL,
  strategy_instance_id BIGINT DEFAULT NULL,
  lease_epoch BIGINT DEFAULT NULL,
  dispatch_status VARCHAR(32) NOT NULL,
  exchange_order_id BIGINT DEFAULT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_live_ticket_credential_key (credential_id, idempotency_key),
  KEY ix_live_ticket_unresolved (dispatch_status, credential_id, symbol)
) ENGINE=InnoDB COMMENT='Durable Binance outbound order tickets; never blindly retry UNKNOWN';

-- Cutover MUST be coordinated with the canonical execution_record/OMS recovery.
-- A stuck or UNKNOWN guard is a safety interlock, not a reason to DELETE rows.
-- Explicit, authenticated recovery must prove exact exchange clientOrderId fact,
-- reconcile the local order ledger and only then clear the guard atomically.

-- ===== V20261014_03__strategy_fill_allocation.sql =====
-- Additive, one-time LIVE strategy-fill allocation ledger (MySQL 8.x / InnoDB).
-- Require backup, all LIVE nodes stopped, and deployment of matching Java code.
-- Never infer strategy ownership from aggregate Binance positions.
-- An exchange fill may be allocated to exactly ONE strategy instance in this
-- conservative single-account-symbol strategy deployment model.
CREATE TABLE quant_strategy_fill_allocation (
  id BIGINT NOT NULL AUTO_INCREMENT,
  credential_id BIGINT NOT NULL,
  symbol VARCHAR(32) NOT NULL,
  exchange_trade_id BIGINT NOT NULL,
  exchange_order_id BIGINT NOT NULL,
  strategy_id BIGINT NOT NULL,
  strategy_instance_id BIGINT NOT NULL,
  side VARCHAR(8) NOT NULL,
  allocated_qty DECIMAL(38,16) NOT NULL,
  allocated_price DECIMAL(38,16) NOT NULL,
  allocated_fee DECIMAL(38,16) NOT NULL,
  allocated_fee_asset VARCHAR(20) NOT NULL,
  realized_pnl DECIMAL(38,16) NOT NULL,
  trade_time DATETIME NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_strategy_exchange_fill (credential_id,symbol,exchange_trade_id),
  KEY ix_strategy_instance_fills (strategy_instance_id,trade_time)
) ENGINE=InnoDB COMMENT='Immutable, idempotent attribution of individual Binance fills';

-- No automatic backfill: historic fills must be reconciled against precise
-- authenticated order provenance before inserting ownership allocations.
-- Never split an unmatched fill proportionally among strategies.

-- ===== V20261014_04__account_settings_intent.sql =====
-- MySQL 8.4 / InnoDB; one-time reviewed additive migration.
-- Run only AFTER V20261014_01, _02, _03 on a verified schema.
-- Stop ALL old LIVE workers first, resolve in-flight orders, backup and rehearse
-- the migration; old versions do not check this contract.
--
-- A configuration intent is NOT an exchange truth snapshot. It records the
-- approved desired setting shared by all AiQuant nodes for one account+symbol.
-- Conflicting strategy settings fail closed even if the dispatch slot becomes
-- free between nodes. There is intentionally no automatic UPDATE path: change
-- requires verified zero positions/orders, coordination and audited operator
-- maintenance while LIVE strategy execution is suspended.
CREATE TABLE quant_live_account_settings_intent (
  credential_id BIGINT NOT NULL,
  symbol VARCHAR(32) NOT NULL,
  margin_mode VARCHAR(12) NOT NULL,
  leverage INT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (credential_id,symbol)
) ENGINE=InnoDB COMMENT='Immutable account-symbol desired Binance margin and leverage contract';

-- Read-only audit (must be empty):
SELECT credential_id, symbol, margin_mode, leverage
FROM quant_live_account_settings_intent
WHERE margin_mode NOT IN ('ISOLATED','CROSSED') OR leverage<1 OR leverage>125;

-- ===== V20261014_05__recovery_case_audit.sql =====
-- Design-only additive migration; requires reviewed canonical production DDL.
-- Do not install before staging backup/restore and migration rehearsal.
CREATE TABLE quant_live_recovery_case (
  case_id BIGINT NOT NULL,
  credential_id BIGINT NOT NULL,
  symbol VARCHAR(32) NOT NULL,
  dispatch_key VARCHAR(160) NOT NULL,
  operation_type VARCHAR(32) NOT NULL,
  requested_by BIGINT NOT NULL,
  reviewed_by BIGINT DEFAULT NULL,
  evidence_sha256 CHAR(64) NOT NULL,
  expected_exchange_order_id BIGINT DEFAULT NULL,
  expected_client_order_id VARCHAR(80) NOT NULL,
  state VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  reviewed_at DATETIME(6) DEFAULT NULL,
  completed_at DATETIME(6) DEFAULT NULL,
  PRIMARY KEY (case_id),
  UNIQUE KEY uq_recovery_case_scope (credential_id, dispatch_key, case_id),
  -- MySQL UNIQUE allows multiple NULLs. Only one PENDING/APPROVED case
  -- may exist per currently active ticket, even under concurrent requests.
  active_case_key VARCHAR(160) GENERATED ALWAYS AS
    (CASE WHEN state IN ('PENDING','APPROVED') THEN dispatch_key ELSE NULL END) STORED,
  UNIQUE KEY uq_active_recovery_case (credential_id, symbol, active_case_key),
  KEY idx_recovery_ticket (credential_id, symbol, dispatch_key),
  KEY idx_recovery_state (state, created_at),
  CONSTRAINT chk_distinct_reviewer CHECK (reviewed_by IS NULL OR reviewed_by <> requested_by),
  CONSTRAINT chk_execution_requires_reviewer CHECK (state NOT IN ('APPROVED','EXECUTED') OR reviewed_by IS NOT NULL),
  CONSTRAINT chk_recovery_state CHECK (state IN ('PENDING','APPROVED','REJECTED','EXECUTED','FAILED'))
) ENGINE=InnoDB COMMENT='Operator two-person recovery approval metadata; no direct unlock';

CREATE TABLE quant_live_recovery_audit (
  audit_id BIGINT NOT NULL,
  case_id BIGINT NOT NULL,
  actor_id BIGINT NOT NULL,
  action VARCHAR(32) NOT NULL,
  evidence_sha256 CHAR(64) NOT NULL,
  detail_hash CHAR(64) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (audit_id),
  KEY idx_case_audit (case_id, created_at),
  CONSTRAINT fk_recovery_audit_case FOREIGN KEY (case_id) REFERENCES quant_live_recovery_case(case_id)
) ENGINE=InnoDB COMMENT='Append-only recovery event metadata; restrict UPDATE/DELETE at DB role';

-- These tables DO NOT enable a recovery API by themselves.
-- Apply least-privilege: application role may INSERT audit events but must not
-- UPDATE or DELETE quant_live_recovery_audit rows. A separate trusted retention
-- and external WORM sink are required for tamper-evident audit.

-- ===== V20261014_06__recovery_audit_immutability.sql =====
-- Apply ONLY after V05 is installed on a verified MySQL 8.4 staging clone.
-- Guard application mistakes; privileged DB administrators can still bypass
-- triggers, so this is NOT a substitute for external WORM evidence.
DELIMITER //
CREATE TRIGGER trg_quant_recovery_audit_no_update
BEFORE UPDATE ON quant_live_recovery_audit
FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Recovery audit records are immutable';
END//
CREATE TRIGGER trg_quant_recovery_audit_no_delete
BEFORE DELETE ON quant_live_recovery_audit
FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Recovery audit records cannot be deleted';
END//
DELIMITER ;

-- Deployment DBA must separately verify:
-- 1. Application DB principal has INSERT/SELECT, not UPDATE/DELETE/ALTER/DROP,
--    on quant_live_recovery_audit; no TRIGGER privilege.
-- 2. Audit exports go to externally managed write-once retention with separate
--    credentials and retention lock. A DBA can otherwise DROP these triggers.
-- 3. Restore and tamper-attempt drills against a disposable MySQL clone.

-- ===== V20261014_07__strategy_position_cycles.sql =====
-- REVIEW-ONLY production migration. Do not install until canonical DDL and
-- authenticated OPEN/CLOSE + hedge-side origin for every fill are verified.
-- No silent backfill of historical or manual Binance orders.
CREATE TABLE quant_strategy_position_cycle (
  id BIGINT NOT NULL,
  credential_id BIGINT NOT NULL,
  symbol VARCHAR(32) NOT NULL,
  strategy_instance_id BIGINT NOT NULL,
  position_side VARCHAR(5) NOT NULL,
  cycle_number BIGINT NOT NULL,
  opened_at DATETIME(6) NOT NULL,
  closed_at DATETIME(6) DEFAULT NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uq_strategy_position_cycle (credential_id,symbol,strategy_instance_id,position_side,cycle_number),
  CONSTRAINT chk_position_side CHECK(position_side IN ('LONG','SHORT'))
) ENGINE=InnoDB;

CREATE TABLE quant_strategy_position_fill_event (
  id BIGINT NOT NULL,
  credential_id BIGINT NOT NULL,
  symbol VARCHAR(32) NOT NULL,
  strategy_id BIGINT NOT NULL,
  strategy_instance_id BIGINT NOT NULL,
  cycle_id BIGINT NOT NULL,
  exchange_trade_id BIGINT NOT NULL,
  exchange_order_id BIGINT NOT NULL,
  position_side VARCHAR(5) NOT NULL,
  fill_role VARCHAR(5) NOT NULL,
  exchange_side VARCHAR(4) NOT NULL,
  quantity DECIMAL(38,16) NOT NULL,
  fill_price DECIMAL(38,16) NOT NULL,
  commission DECIMAL(38,16) NOT NULL,
  commission_asset VARCHAR(20) NOT NULL,
  realized_pnl DECIMAL(38,16) NOT NULL,
  trade_time DATETIME(6) NOT NULL,
  recorded_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY(id),
  UNIQUE KEY uq_exact_position_fill (credential_id,symbol,exchange_trade_id),
  KEY ix_strategy_cycle (cycle_id,trade_time),
  CONSTRAINT fk_strategy_position_cycle FOREIGN KEY(cycle_id) REFERENCES quant_strategy_position_cycle(id),
  CONSTRAINT chk_event_role CHECK(fill_role IN ('OPEN','CLOSE')),
  CONSTRAINT chk_event_side CHECK(exchange_side IN ('BUY','SELL')),
  CONSTRAINT chk_event_position_side CHECK(position_side IN ('LONG','SHORT')),
  CONSTRAINT chk_event_qty CHECK(quantity>0 AND fill_price>0 AND commission>=0)
) ENGINE=InnoDB;

-- A complete online projector still needs exclusive ordered per-strategy
-- cycle claims, replay/high-water marks, verified trade-side intent, and
-- exact account reconciliation before writing to these tables.
-- Do not enable it from this schema alone.

-- ===== V20261014_08__strategy_position_inventory.sql =====
-- REVIEW ONLY. Install after V07 on a restored staging clone and before
-- enabling the manually authorized position projector.
-- Only one inventory aggregate row per strategy instance / position side.
CREATE TABLE quant_strategy_position_inventory (
  credential_id BIGINT NOT NULL,
  symbol VARCHAR(32) NOT NULL,
  strategy_instance_id BIGINT NOT NULL,
  position_side VARCHAR(5) NOT NULL,
  strategy_id BIGINT NOT NULL,
  open_quantity DECIMAL(38,16) NOT NULL DEFAULT 0,
  cost_basis DECIMAL(38,16) NOT NULL DEFAULT 0,
  realized_pnl DECIMAL(38,16) NOT NULL DEFAULT 0,
  commissions DECIMAL(38,16) NOT NULL DEFAULT 0,
  commission_asset VARCHAR(20) DEFAULT NULL,
  cycle_number BIGINT NOT NULL DEFAULT 0,
  cycle_id BIGINT NULL,
  last_trade_time DATETIME(6) NULL,
  last_trade_id BIGINT NULL,
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (credential_id,symbol,strategy_instance_id,position_side),
  CONSTRAINT chk_inventory_position_side CHECK (position_side IN ('LONG','SHORT')),
  CONSTRAINT chk_inventory_nonnegative CHECK (open_quantity>=0 AND cost_basis>=0 AND commissions>=0),
  CONSTRAINT chk_inventory_closed_cost CHECK (open_quantity<>0 OR cost_basis=0)
) ENGINE=InnoDB;

-- The projection transaction must use SELECT ... FOR UPDATE on this primary
-- key, validate role from an authenticated order intent, compare strict
-- trade_time/trade_id ordering, and insert a unique exchange fill event.
-- Historical gaps, manual trades and BOTH/one-way ambiguous reversals
-- are quarantined, never projected by guesswork.

-- ===== V20261014_09__strategy_order_intent.sql =====
-- REVIEW-ONLY: persisted, immutable execution provenance required before
-- projecting any LIVE strategy fill. Do not derive OPEN/CLOSE from BUY/SELL.
CREATE TABLE quant_strategy_order_intent (
  credential_id BIGINT NOT NULL,
  symbol VARCHAR(32) NOT NULL,
  exchange_order_id BIGINT NOT NULL,
  strategy_id BIGINT NOT NULL,
  strategy_instance_id BIGINT NOT NULL,
  position_side VARCHAR(5) NOT NULL,
  fill_role VARCHAR(5) NOT NULL,
  exchange_side VARCHAR(4) NOT NULL,
  client_order_id VARCHAR(80) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (credential_id,symbol,exchange_order_id),
  UNIQUE KEY uq_strategy_intent_client (credential_id,symbol,client_order_id),
  CONSTRAINT chk_intent_side CHECK (position_side IN ('LONG','SHORT')),
  CONSTRAINT chk_intent_role CHECK (fill_role IN ('OPEN','CLOSE')),
  CONSTRAINT chk_intent_exchange_side CHECK (exchange_side IN ('BUY','SELL'))
) ENGINE=InnoDB;

-- No automatic backfill. The execution service must persist authenticated
-- immutable intent BEFORE enabling the projector. Reconciliation must not
-- fabricate provenance from mutable order fields or reduceOnly.

-- ===== V20261014_10__pre_dispatch_strategy_intent.sql =====
-- REVIEW-ONLY additive MySQL 8.4 migration.
-- Pre-send, immutable provenance must commit with the dispatch ticket before
-- any LIVE Binance HTTP leaves the process. Never infer hedge side after a
-- process crash from a mutable aggregate position.
CREATE TABLE quant_strategy_pre_dispatch_intent (
  credential_id BIGINT NOT NULL,
  symbol VARCHAR(32) NOT NULL,
  idempotency_key VARCHAR(160) NOT NULL,
  client_order_id VARCHAR(80) NOT NULL,
  strategy_id BIGINT NOT NULL,
  strategy_instance_id BIGINT NOT NULL,
  operation_type VARCHAR(5) NOT NULL,
  position_side VARCHAR(5) NOT NULL,
  exchange_side VARCHAR(4) NOT NULL,
  lease_epoch BIGINT NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (credential_id,idempotency_key),
  UNIQUE KEY uq_pre_dispatch_client (credential_id,symbol,client_order_id),
  KEY idx_pre_dispatch_strategy (strategy_instance_id,created_at),
  CONSTRAINT chk_pre_dispatch_role CHECK (operation_type IN ('OPEN','CLOSE')),
  CONSTRAINT chk_pre_dispatch_position CHECK (position_side IN ('LONG','SHORT')),
  CONSTRAINT chk_pre_dispatch_side CHECK (exchange_side IN ('BUY','SELL')),
  CONSTRAINT chk_pre_dispatch_epoch CHECK (lease_epoch>0),
  CONSTRAINT fk_pre_dispatch_ticket FOREIGN KEY (credential_id,idempotency_key)
    REFERENCES quant_order_dispatch_ticket(credential_id,idempotency_key)
) ENGINE=InnoDB;

-- The row is append-only: the remote outcome is separately represented by the
-- order dispatch ticket and quant_strategy_order_intent (V09). Do not delete
-- unknown pre-send facts; restore them from durable backup before resuming LIVE.
