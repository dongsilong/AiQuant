-- V20261112_02: use the Binance exchange order identity as the durable order key.
-- Run against the application's active quantdb after taking a database backup.
--
-- IMPORTANT:
-- 1. Run the duplicate check below first. It must return no rows.
-- 2. If uk_quant_order_exchange already exists, skip the first ALTER TABLE.
-- 3. If uk_quant_order_client does not exist, skip its DROP INDEX statement.
-- 4. If idx_quant_order_client already exists, skip its ADD INDEX statement.
-- The exchange identity index is created BEFORE dropping the old client-order unique
-- index, so duplicate exchange identities fail safely without removing the old constraint.

SELECT credential_id, symbol, exchange_order_id, COUNT(*) AS duplicate_count
FROM quant_order
WHERE exchange_order_id IS NOT NULL
GROUP BY credential_id, symbol, exchange_order_id
HAVING COUNT(*) > 1;

-- Execute only when uk_quant_order_exchange is absent:
ALTER TABLE quant_order
    ADD UNIQUE KEY uk_quant_order_exchange (credential_id, symbol, exchange_order_id);

-- Execute only when uk_quant_order_client exists:
ALTER TABLE quant_order
    DROP INDEX uk_quant_order_client;

-- Execute only when idx_quant_order_client is absent:
ALTER TABLE quant_order
    ADD INDEX idx_quant_order_client (credential_id, symbol, client_order_id);
