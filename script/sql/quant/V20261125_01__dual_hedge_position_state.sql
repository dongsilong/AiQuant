-- 双向持仓状态扩展：旧策略统一保留 SINGLE 语义，不改变旧持仓周期和收益字段。
DELIMITER $
DROP PROCEDURE IF EXISTS migrate_dual_hedge_position_state$
CREATE PROCEDURE migrate_dual_hedge_position_state()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = DATABASE() AND table_name = 'quant_strategy_position_state'
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Dual hedge state migration blocked: quant_strategy_position_state does not exist';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='quant_strategy_position_state' AND column_name='leg_key') THEN
        ALTER TABLE quant_strategy_position_state
            ADD COLUMN leg_key varchar(10) NOT NULL DEFAULT 'SINGLE' COMMENT '持仓腿：SINGLE/LONG/SHORT' AFTER instance_id;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='quant_strategy_position_state' AND column_name='hedge_round_key') THEN
        ALTER TABLE quant_strategy_position_state
            ADD COLUMN hedge_round_key varchar(36) NULL DEFAULT NULL COMMENT '双向持仓同轮开仓关联标识' AFTER leg_key;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='quant_strategy_position_state' AND column_name='entry_equity_snapshot') THEN
        ALTER TABLE quant_strategy_position_state
            ADD COLUMN entry_equity_snapshot decimal(32,16) NULL DEFAULT NULL COMMENT '开仓前账户总权益快照' AFTER initial_margin;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='quant_strategy_position_state' AND column_name='realized_pnl') THEN
        ALTER TABLE quant_strategy_position_state
            ADD COLUMN realized_pnl decimal(32,16) NOT NULL DEFAULT 0 COMMENT '当前持仓腿已实现盈亏累计' AFTER entry_equity_snapshot;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='quant_strategy_position_state' AND column_name='trailing_armed') THEN
        ALTER TABLE quant_strategy_position_state
            ADD COLUMN trailing_armed char(1) NOT NULL DEFAULT '0' COMMENT '移动止盈是否已武装' AFTER first_take_profit_triggered;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='quant_strategy_position_state' AND column_name='trailing_stop_roi') THEN
        ALTER TABLE quant_strategy_position_state
            ADD COLUMN trailing_stop_roi decimal(32,16) NULL DEFAULT NULL COMMENT '当前移动止盈线ROI' AFTER trailing_armed;
    END IF;

    UPDATE quant_strategy_position_state SET leg_key='SINGLE' WHERE leg_key IS NULL OR leg_key='';

    IF EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema=DATABASE() AND table_name='quant_strategy_position_state'
          AND index_name='uk_quant_strategy_position_state_instance'
    ) THEN
        ALTER TABLE quant_strategy_position_state DROP INDEX uk_quant_strategy_position_state_instance;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema=DATABASE() AND table_name='quant_strategy_position_state'
          AND index_name='uk_quant_strategy_position_state_instance_leg'
    ) THEN
        IF EXISTS (
            SELECT 1 FROM quant_strategy_position_state
            GROUP BY instance_id, leg_key HAVING COUNT(*) > 1
        ) THEN
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'Dual hedge state migration blocked: duplicate (instance_id, leg_key) rows exist';
        END IF;
        ALTER TABLE quant_strategy_position_state
            ADD UNIQUE INDEX uk_quant_strategy_position_state_instance_leg (instance_id, leg_key);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema=DATABASE() AND table_name='quant_strategy_position_state'
          AND index_name='idx_quant_strategy_position_state_round'
    ) THEN
        ALTER TABLE quant_strategy_position_state
            ADD INDEX idx_quant_strategy_position_state_round (hedge_round_key);
    END IF;
END$
CALL migrate_dual_hedge_position_state()$
DROP PROCEDURE migrate_dual_hedge_position_state$
DELIMITER ;
