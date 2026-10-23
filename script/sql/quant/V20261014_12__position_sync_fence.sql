-- Required before deploying the multi-node LIVE position reconciliation build.
-- InnoDB row lock serializes writes to one credential across all application nodes.
-- snapshot_started_at is deliberately nullable for the first completed REST snapshot.
CREATE TABLE IF NOT EXISTS quant_position_sync_fence (
  credential_id BIGINT NOT NULL,
  snapshot_started_at DATETIME(6) NULL,
  updated_at DATETIME(6) NULL,
  PRIMARY KEY (credential_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
