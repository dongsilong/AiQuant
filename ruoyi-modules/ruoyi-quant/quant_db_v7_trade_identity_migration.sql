-- V7: align the local trade-ledger uniqueness key with Binance trade-ID scope.
-- Run once against an existing V6 database after taking a backup.
-- The old unique key (credential_id, exchange_trade_id) incorrectly treats a trade ID
-- as globally unique across symbols. The replacement key includes symbol.
-- This is a one-time migration; do not blindly rerun after the old index has been dropped.
ALTER TABLE `quant_trade`
    DROP INDEX `uk_quant_trade`,
    ADD UNIQUE KEY `uk_quant_trade_symbol` (`credential_id`, `symbol`, `exchange_trade_id`);
