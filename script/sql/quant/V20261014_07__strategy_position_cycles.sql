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
