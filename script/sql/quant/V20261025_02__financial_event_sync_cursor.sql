-- Review-only additive schema for durable Binance income/funding history reconciliation.
-- NOT an automatic migration or a canonical production DDL baseline.
-- Preconditions: disable LIVE/runtime on every node, verify an encrypted backup/restore,
-- inspect existing financial event data and schema, and rehearse on a restored clone.
-- Do not run blindly against production.

CREATE TABLE quant_financial_event_sync_cursor (
  credential_id BIGINT NOT NULL,
  last_complete_to DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (credential_id)
) ENGINE=InnoDB COMMENT='Monotonic watermark for fully persisted Binance income history';

-- The watermark advances in the same transaction as financial-event persistence.
-- The collector re-queries bounded windows and splits any 1000-row saturated page.
-- If the cursor is over 180 days behind, reconciliation advances in 180-day chunks
-- and remains DEGRADED until it catches up; a future cursor fails closed.
