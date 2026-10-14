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
