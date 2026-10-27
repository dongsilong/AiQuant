-- V20261026_01: move financial-event idempotency to the canonical exchange identity.
-- Run against the configured quant database after a verified backup.
-- Existing tranId=0 rows require a source-backed fingerprint; do not guess their identity or
-- timezone. Refuse this migration before altering anything if such rows exist.
DELIMITER $$
CREATE PROCEDURE migrate_financial_event_identity_key()
BEGIN
    IF EXISTS (
        SELECT 1 FROM quant_financial_event WHERE exchange_transaction_id = 0 LIMIT 1
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Financial-event identity migration blocked: tranId=0 rows require verified canonical backfill before migration';
    END IF;
END$$
CALL migrate_financial_event_identity_key()$$
DROP PROCEDURE migrate_financial_event_identity_key$$
DELIMITER ;

ALTER TABLE quant_financial_event
    ADD COLUMN event_identity_key varchar(128) NULL COMMENT 'Canonical immutable exchange financial-event identity';

UPDATE quant_financial_event
SET event_identity_key = CONCAT('T:', event_type, ':', exchange_transaction_id)
WHERE exchange_transaction_id <> 0
  AND (event_identity_key IS NULL OR event_identity_key = '');

-- Every existing non-zero Binance transaction must now have a deterministic identity.
ALTER TABLE quant_financial_event
    MODIFY COLUMN event_identity_key varchar(128) NOT NULL COMMENT 'Canonical immutable exchange financial-event identity',
    DROP INDEX uk_quant_financial_event,
    ADD UNIQUE KEY uk_quant_financial_event_identity (credential_id, event_identity_key);
