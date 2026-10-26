-- Add a durable idempotency key for Binance income events.
-- Non-zero tranId identity remains incomeType + tranId. Binance may return tranId=0
-- for TRANSFER rows; those rows use a SHA-256 fingerprint of immutable income facts.
-- Back up the database before applying this migration.
ALTER TABLE quant_financial_event
    ADD COLUMN event_identity_key VARCHAR(160) NULL;

UPDATE quant_financial_event
SET event_identity_key = CASE
    WHEN exchange_transaction_id IS NOT NULL AND exchange_transaction_id <> 0
        THEN CONCAT('T:', event_type, ':', exchange_transaction_id)
    ELSE CONCAT(
        'Z:',
        SHA2(
            CONCAT_WS(CHAR(31),
                COALESCE(symbol, ''),
                COALESCE(event_type, ''),
                COALESCE(
                    TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM
                        CAST(CAST(amount AS DECIMAL(65,20)) AS CHAR))),
                    ''
                ),
                COALESCE(asset, ''),
                COALESCE(DATE_FORMAT(event_time, '%Y-%m-%d %H:%i:%s'), ''),
                COALESCE(info, '')
            ),
            256
        )
    )
END;

-- Previous migration V20261024_01__financial_event_income_type_identity.sql establishes
-- the known composite index name and removes the older tranId-only index.
ALTER TABLE quant_financial_event
    DROP INDEX uk_quant_financial_event,
    MODIFY COLUMN event_identity_key VARCHAR(160) NOT NULL,
    ADD UNIQUE KEY uk_quant_financial_event_identity (credential_id, event_identity_key);
