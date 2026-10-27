-- V20261027_02: align existing quant_order schemas with the order entity and mapper.
-- post_fill_wallet_balance is an account wallet-balance observation after an order fill.
-- It is NOT the balance delta or net PnL attributable to that order/position.
-- The migration is idempotent and does not rewrite existing financial/trade facts.

DELIMITER $
DROP PROCEDURE IF EXISTS migrate_quant_order_balance_snapshot$
CREATE PROCEDURE migrate_quant_order_balance_snapshot()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'quant_order' AND column_name = 'leverage_snapshot'
    ) THEN
        ALTER TABLE quant_order
            ADD COLUMN leverage_snapshot int NULL DEFAULT NULL COMMENT '订单受理时冻结的杠杆';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'quant_order' AND column_name = 'price_tick_size_snapshot'
    ) THEN
        ALTER TABLE quant_order
            ADD COLUMN price_tick_size_snapshot decimal(32, 16) NULL DEFAULT NULL COMMENT '订单受理时冻结的价格最小变动单位';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'quant_order' AND column_name = 'post_fill_wallet_balance'
    ) THEN
        ALTER TABLE quant_order
            ADD COLUMN post_fill_wallet_balance decimal(32, 16) NULL DEFAULT NULL COMMENT '成交后账户钱包余额快照，不代表该笔交易造成的余额变化';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'quant_order' AND column_name = 'post_fill_balance_at'
    ) THEN
        ALTER TABLE quant_order
            ADD COLUMN post_fill_balance_at datetime NULL DEFAULT NULL COMMENT '成交后账户余额快照核实时间（UTC）';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'quant_order' AND column_name = 'post_fill_balance_requested_at'
    ) THEN
        ALTER TABLE quant_order
            ADD COLUMN post_fill_balance_requested_at datetime NULL DEFAULT NULL COMMENT '最近一次成交后余额快照请求时间（UTC）';
    END IF;
END$
CALL migrate_quant_order_balance_snapshot()$
DROP PROCEDURE migrate_quant_order_balance_snapshot$
DELIMITER ;
