-- V20261112_01: use the Binance exchange order identity as the durable order key.
-- Back up the database before running. This migration intentionally aborts if duplicate
-- exchange identities already exist; resolve those rows manually before retrying.
DELIMITER $
DROP PROCEDURE IF EXISTS migrate_quant_order_exchange_identity$
CREATE PROCEDURE migrate_quant_order_exchange_identity()
BEGIN
    DECLARE duplicate_count BIGINT DEFAULT 0;
    IF EXISTS (SELECT 1 FROM information_schema.tables
               WHERE table_schema=DATABASE() AND table_name='quant_order') THEN
        SELECT COUNT(*) INTO duplicate_count
        FROM (
            SELECT credential_id, symbol, exchange_order_id
            FROM quant_order
            WHERE exchange_order_id IS NOT NULL
            GROUP BY credential_id, symbol, exchange_order_id
            HAVING COUNT(*) > 1
        ) duplicates_found;
        IF duplicate_count > 0 THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT='quant_order has duplicate exchange identities; resolve before migration';
        END IF;
        IF EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema=DATABASE() AND table_name='quant_order'
                     AND index_name='uk_quant_order_client') THEN
            ALTER TABLE quant_order DROP INDEX uk_quant_order_client;
        END IF;
        IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                       WHERE table_schema=DATABASE() AND table_name='quant_order'
                         AND index_name='idx_quant_order_client') THEN
            ALTER TABLE quant_order
                ADD INDEX idx_quant_order_client (credential_id, symbol, client_order_id);
        END IF;
        IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                       WHERE table_schema=DATABASE() AND table_name='quant_order'
                         AND index_name='uk_quant_order_exchange') THEN
            ALTER TABLE quant_order
                ADD UNIQUE KEY uk_quant_order_exchange (credential_id, symbol, exchange_order_id);
        END IF;
    END IF;
END$
CALL migrate_quant_order_exchange_identity()$
DROP PROCEDURE migrate_quant_order_exchange_identity$
DELIMITER ;
