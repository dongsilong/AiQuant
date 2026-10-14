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
