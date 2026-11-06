-- Persist Binance userTrades.positionSide without rewriting historical exchange facts.
SET @quant_trade_position_side_sql = IF(
    EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = DATABASE() AND table_name = 'quant_trade'
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'quant_trade'
          AND column_name = 'exchange_position_side'
    ),
    'ALTER TABLE quant_trade ADD COLUMN exchange_position_side varchar(10) NULL DEFAULT NULL COMMENT ''Binance userTrades 原始持仓方向'' AFTER side',
    'SELECT 1'
);
PREPARE quant_trade_position_side_stmt FROM @quant_trade_position_side_sql;
EXECUTE quant_trade_position_side_stmt;
DEALLOCATE PREPARE quant_trade_position_side_stmt;

SET @quant_fill_journal_position_side_sql = IF(
    EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = DATABASE() AND table_name = 'quant_strategy_fill_journal'
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'quant_strategy_fill_journal'
          AND column_name = 'exchange_position_side'
    ),
    'ALTER TABLE quant_strategy_fill_journal ADD COLUMN exchange_position_side varchar(10) NULL DEFAULT NULL COMMENT ''Binance userTrades 原始持仓方向'' AFTER side',
    'SELECT 1'
);
PREPARE quant_fill_journal_position_side_stmt FROM @quant_fill_journal_position_side_sql;
EXECUTE quant_fill_journal_position_side_stmt;
DEALLOCATE PREPARE quant_fill_journal_position_side_stmt;
