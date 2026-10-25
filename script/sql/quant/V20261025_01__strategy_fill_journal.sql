-- Review-only additive migration for the active raw-fill journal and monotonic sync cursor.
-- NOT an automatic migration and NOT a complete production schema baseline.
-- Preconditions: disable LIVE/runtime on every node, resolve outbound UNKNOWN tickets,
-- take and restore-test an encrypted backup, inspect SHOW CREATE TABLE and duplicates,
-- rehearse on a production clone, and review rollback/forward recovery.
-- Do not run blindly against production. Both tables are used by the current fill
-- reconciliation path and must be present before enabling that path.

CREATE TABLE quant_strategy_fill_journal (
  id BIGINT NOT NULL,
  credential_id BIGINT NOT NULL,
  symbol VARCHAR(32) NOT NULL,
  exchange_trade_id BIGINT NOT NULL,
  exchange_order_id BIGINT DEFAULT NULL,
  side VARCHAR(8) DEFAULT NULL,
  quantity DECIMAL(38,16) DEFAULT NULL,
  price DECIMAL(38,16) DEFAULT NULL,
  commission DECIMAL(38,16) DEFAULT NULL,
  commission_asset VARCHAR(20) DEFAULT NULL,
  realized_pnl DECIMAL(38,16) DEFAULT NULL,
  trade_time DATETIME(3) DEFAULT NULL,
  attribution_status VARCHAR(24) NOT NULL DEFAULT 'CAPTURED',
  status_reason VARCHAR(500) DEFAULT NULL,
  attempt_count INT NOT NULL DEFAULT 0,
  last_attempt_at DATETIME(3) DEFAULT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uq_fill_journal_trade (credential_id, symbol, exchange_trade_id),
  KEY ix_fill_journal_pending (credential_id, attribution_status, last_attempt_at, id),
  KEY ix_fill_journal_unresolved (credential_id, symbol, attribution_status, trade_time, id),
  CONSTRAINT chk_fill_journal_attempt_count CHECK (attempt_count >= 0),
  CONSTRAINT chk_fill_journal_status CHECK (attribution_status IN
    ('CAPTURED','WAITING_ORDER','PENDING_FACTS','ATTRIBUTED','CONFLICT'))
) ENGINE=InnoDB COMMENT='Immutable Binance fill facts with retryable attribution state';

CREATE TABLE quant_strategy_fill_sync_cursor (
  credential_id BIGINT NOT NULL,
  symbol VARCHAR(32) NOT NULL,
  last_complete_to DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (credential_id, symbol)
) ENGINE=InnoDB COMMENT='Monotonic checkpoint for fully persisted Binance fill history';

-- After a reviewed staging rehearsal, run production-preflight.sql and verify
-- both tables are InnoDB, the journal trade key is unique, and the cursor key
-- is unique. This migration does not backfill history or infer strategy ownership.
