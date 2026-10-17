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
