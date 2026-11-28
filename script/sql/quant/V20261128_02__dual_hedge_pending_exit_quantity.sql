-- 本地双开退出必须持久化预期数量，进程重启后才能核实成交是否与原动作完全一致。
ALTER TABLE quant_strategy_position_state
    ADD COLUMN pending_target_quantity decimal(32,16) DEFAULT NULL COMMENT '待确认本地平仓动作的目标数量' AFTER pending_order_time;
