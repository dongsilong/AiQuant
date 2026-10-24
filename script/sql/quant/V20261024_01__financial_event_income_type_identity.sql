-- Fix Binance USD-M Futures income identity:
-- tranId is unique only within the same incomeType, not across all income types.
-- Back up the database before applying. Run the duplicate check first; it must return no rows.
SELECT
    credential_id,
    exchange_transaction_id,
    event_type,
    COUNT(*) AS duplicate_count
FROM quant_financial_event
GROUP BY credential_id, exchange_transaction_id, event_type
HAVING COUNT(*) > 1;

-- Replace the incorrect credential + tranId uniqueness with the exchange-defined identity.
ALTER TABLE quant_financial_event
    DROP INDEX uk_quant_financial_event,
    ADD UNIQUE KEY uk_quant_financial_event
        (credential_id, exchange_transaction_id, event_type);
