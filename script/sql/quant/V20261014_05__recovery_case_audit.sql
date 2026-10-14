-- Design-only additive migration; requires reviewed canonical production DDL.
-- Do not install before staging backup/restore and migration rehearsal.
CREATE TABLE quant_live_recovery_case (
  case_id BIGINT NOT NULL,
  credential_id BIGINT NOT NULL,
  symbol VARCHAR(32) NOT NULL,
  dispatch_key VARCHAR(160) NOT NULL,
  operation_type VARCHAR(32) NOT NULL,
  requested_by BIGINT NOT NULL,
  reviewed_by BIGINT DEFAULT NULL,
  evidence_sha256 CHAR(64) NOT NULL,
  expected_exchange_order_id BIGINT DEFAULT NULL,
  expected_client_order_id VARCHAR(80) NOT NULL,
  state VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  reviewed_at DATETIME(6) DEFAULT NULL,
  completed_at DATETIME(6) DEFAULT NULL,
  PRIMARY KEY (case_id),
  UNIQUE KEY uq_recovery_case_scope (credential_id, dispatch_key, case_id),
  -- MySQL UNIQUE allows multiple NULLs. Only one PENDING/APPROVED case
  -- may exist per currently active ticket, even under concurrent requests.
  active_case_key VARCHAR(160) GENERATED ALWAYS AS
    (CASE WHEN state IN ('PENDING','APPROVED') THEN dispatch_key ELSE NULL END) STORED,
  UNIQUE KEY uq_active_recovery_case (credential_id, symbol, active_case_key),
  KEY idx_recovery_ticket (credential_id, symbol, dispatch_key),
  KEY idx_recovery_state (state, created_at),
  CONSTRAINT chk_distinct_reviewer CHECK (reviewed_by IS NULL OR reviewed_by <> requested_by),
  CONSTRAINT chk_execution_requires_reviewer CHECK (state NOT IN ('APPROVED','EXECUTED') OR reviewed_by IS NOT NULL),
  CONSTRAINT chk_recovery_state CHECK (state IN ('PENDING','APPROVED','REJECTED','EXECUTED','FAILED'))
) ENGINE=InnoDB COMMENT='Operator two-person recovery approval metadata; no direct unlock';

CREATE TABLE quant_live_recovery_audit (
  audit_id BIGINT NOT NULL,
  case_id BIGINT NOT NULL,
  actor_id BIGINT NOT NULL,
  action VARCHAR(32) NOT NULL,
  evidence_sha256 CHAR(64) NOT NULL,
  detail_hash CHAR(64) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (audit_id),
  KEY idx_case_audit (case_id, created_at),
  CONSTRAINT fk_recovery_audit_case FOREIGN KEY (case_id) REFERENCES quant_live_recovery_case(case_id)
) ENGINE=InnoDB COMMENT='Append-only recovery event metadata; restrict UPDATE/DELETE at DB role';

-- These tables DO NOT enable a recovery API by themselves.
-- Apply least-privilege: application role may INSERT audit events but must not
-- UPDATE or DELETE quant_live_recovery_audit rows. A separate trusted retention
-- and external WORM sink are required for tamper-evident audit.
