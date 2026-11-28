-- 双开腿的交易所保护委托确认状态与持仓状态分开记录，避免未挂保护时误认为可以继续自动管理。
ALTER TABLE quant_strategy_position_state
    ADD COLUMN exchange_protection_ready tinyint(1) NOT NULL DEFAULT 0 COMMENT '双开腿 Binance 硬止损、首次止盈和回本保护是否已确认生效' AFTER unsafe_recovery;
