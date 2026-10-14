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
