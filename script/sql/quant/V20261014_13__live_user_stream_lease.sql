-- Account-scoped private user stream lease. Apply before enabling event-driven mode
-- on multiple application nodes. Lease is renewed using DB server time.
CREATE TABLE IF NOT EXISTS quant_live_user_stream_lease (
  credential_id BIGINT NOT NULL,
  owner_token VARCHAR(96) NOT NULL,
  lease_epoch BIGINT NOT NULL DEFAULT 1,
  lease_until DATETIME(6) NOT NULL,
  PRIMARY KEY (credential_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
