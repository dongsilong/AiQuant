-- MySQL 8.4 / InnoDB; one-time reviewed additive migration.
-- Run only AFTER V20261014_01, _02, _03 on a verified schema.
-- Stop ALL old LIVE workers first, resolve in-flight orders, backup and rehearse
-- the migration; old versions do not check this contract.
--
-- A configuration intent is NOT an exchange truth snapshot. It records the
-- approved desired setting shared by all AiQuant nodes for one account+symbol.
-- Conflicting strategy settings fail closed even if the dispatch slot becomes
-- free between nodes. There is intentionally no automatic UPDATE path: change
-- requires verified zero positions/orders, coordination and audited operator
-- maintenance while LIVE strategy execution is suspended.
CREATE TABLE quant_live_account_settings_intent (
  credential_id BIGINT NOT NULL,
  symbol VARCHAR(32) NOT NULL,
  margin_mode VARCHAR(12) NOT NULL,
  leverage INT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (credential_id,symbol)
) ENGINE=InnoDB COMMENT='Immutable account-symbol desired Binance margin and leverage contract';

-- Read-only audit (must be empty):
SELECT credential_id, symbol, margin_mode, leverage
FROM quant_live_account_settings_intent
WHERE margin_mode NOT IN ('ISOLATED','CROSSED') OR leverage<1 OR leverage>125;
