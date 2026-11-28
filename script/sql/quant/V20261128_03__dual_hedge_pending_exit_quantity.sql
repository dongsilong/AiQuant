-- 持久化双开本地保护在途平仓数量，用于重启后精确核实同一笔 CLOSE。
ALTER TABLE quant_strategy_position_state
    ADD COLUMN pending_target_quantity decimal(32,16) DEFAULT NULL COMMENT '待确认本地平仓动作的目标数量' AFTER pending_order_time;
