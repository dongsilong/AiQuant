-- Add an execution toggle separate from quant_strategy_symbol.status.
-- This is a reviewed, manual additive migration; it is NOT auto-applied by the application.
-- Before running: stop LIVE strategy supervisors on every node, verify SHOW CREATE TABLE,
-- confirm the column is absent, back up the database, and check the rollback plan.
ALTER TABLE quant_strategy_symbol
  ADD COLUMN runtime_enabled TINYINT(1) NOT NULL DEFAULT 1
  COMMENT '单交易对运行开关；关闭时禁止新开仓';
