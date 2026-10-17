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
