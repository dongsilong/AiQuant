-- V20261104_01: isolated, auditable rebuild of strategy open inventory.
-- Apply after V20261102_01 and V20261103_01 on the configured quant database.
-- Back up the database first. This migration creates only empty staging tables;
-- it does not rebuild or overwrite the live inventory.

ALTER TABLE quant_strategy_position_state
    ADD COLUMN recovery_reason varchar(64) DEFAULT NULL COMMENT '可自动解除的恢复阻断类型' AFTER unsafe_recovery;

CREATE TABLE IF NOT EXISTS quant_strategy_position_rebuild_run (
    id bigint NOT NULL COMMENT '重建任务 ID',
    credential_id bigint NOT NULL COMMENT '量化凭证 ID',
    symbol varchar(32) NOT NULL COMMENT '交易对',
    active_run_key varchar(64) DEFAULT NULL COMMENT '进行中任务唯一键，结束后释放',
    status varchar(16) NOT NULL COMMENT 'BUILDING/READY/VERIFIED/FAILED',
    history_from datetime(3) NOT NULL COMMENT 'Binance 可验证历史窗口起点 UTC',
    history_to datetime(3) NOT NULL COMMENT '开始重建时的历史窗口终点 UTC',
    source_max_journal_id bigint NOT NULL DEFAULT 0 COMMENT '原始成交日志高水位',
    source_fill_count bigint NOT NULL DEFAULT 0 COMMENT '窗口内 ATTRIBUTED 成交数',
    processed_count bigint NOT NULL DEFAULT 0 COMMENT '已重放成交数',
    cursor_trade_time datetime(3) DEFAULT NULL COMMENT '稳定分页游标成交时间 UTC',
    cursor_trade_id bigint DEFAULT NULL COMMENT '稳定分页游标 Binance 成交 ID',
    exchange_long_quantity decimal(32,16) DEFAULT NULL COMMENT '核验时 Binance LONG 数量',
    exchange_short_quantity decimal(32,16) DEFAULT NULL COMMENT '核验时 Binance SHORT 数量',
    rebuilt_long_quantity decimal(32,16) DEFAULT NULL COMMENT '影子 LONG 数量',
    rebuilt_short_quantity decimal(32,16) DEFAULT NULL COMMENT '影子 SHORT 数量',
    failure_reason varchar(500) DEFAULT NULL COMMENT '失败原因',
    created_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_quant_strategy_position_rebuild_run (active_run_key),
    KEY idx_quant_strategy_position_rebuild_status (credential_id,symbol,status,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='策略库存影子重建任务';

CREATE TABLE IF NOT EXISTS quant_strategy_position_rebuild_inventory (
    run_id bigint NOT NULL COMMENT '重建任务 ID',
    credential_id bigint NOT NULL COMMENT '量化凭证 ID',
    symbol varchar(32) NOT NULL COMMENT '交易对',
    strategy_instance_id bigint NOT NULL COMMENT '策略实例 ID',
    position_side varchar(8) NOT NULL COMMENT 'LONG/SHORT',
    strategy_id bigint NOT NULL COMMENT '策略 ID',
    open_quantity decimal(32,16) NOT NULL DEFAULT 0 COMMENT '影子未平仓数量',
    cost_basis decimal(32,16) NOT NULL DEFAULT 0 COMMENT '影子剩余持仓成本',
    realized_pnl decimal(32,16) NOT NULL DEFAULT 0 COMMENT '保留的历史已实现盈亏快照',
    preserved_realized_pnl decimal(32,16) NOT NULL DEFAULT 0 COMMENT '重建前历史已实现盈亏基线',
    cycle_number bigint NOT NULL DEFAULT 0 COMMENT '窗口内重放得到的周期序号',
    preserved_cycle_number bigint NOT NULL DEFAULT 0 COMMENT '窗口前周期序号下限',
    cycle_id bigint DEFAULT NULL COMMENT '影子当前未平仓周期',
    last_trade_time datetime(3) DEFAULT NULL COMMENT '窗口内最后成交时间 UTC',
    last_trade_id bigint DEFAULT NULL COMMENT '窗口内最后 Binance 成交 ID',
    preserved_last_trade_time datetime(3) DEFAULT NULL COMMENT '窗口前最后成交时间基线',
    preserved_last_trade_id bigint DEFAULT NULL COMMENT '窗口前最后 Binance 成交 ID 基线',
    PRIMARY KEY (run_id,strategy_instance_id,position_side),
    KEY idx_quant_strategy_position_rebuild_inventory_owner (credential_id,symbol,position_side,run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='策略库存影子重建结果';

CREATE TABLE IF NOT EXISTS quant_strategy_position_rebuild_fill (
    run_id bigint NOT NULL COMMENT '重建任务 ID',
    credential_id bigint NOT NULL COMMENT '量化凭证 ID',
    symbol varchar(32) NOT NULL COMMENT '交易对',
    strategy_id bigint NOT NULL COMMENT '策略 ID',
    strategy_instance_id bigint NOT NULL COMMENT '策略实例 ID',
    cycle_id bigint NOT NULL COMMENT '原始策略持仓周期 ID',
    exchange_trade_id bigint NOT NULL COMMENT 'Binance 成交 ID',
    exchange_order_id bigint NOT NULL COMMENT 'Binance 订单 ID',
    position_side varchar(8) NOT NULL COMMENT 'LONG/SHORT',
    fill_role varchar(8) NOT NULL COMMENT 'OPEN/CLOSE',
    exchange_side varchar(4) NOT NULL COMMENT 'BUY/SELL',
    quantity decimal(32,16) NOT NULL COMMENT '成交数量',
    fill_price decimal(32,16) NOT NULL COMMENT '成交价格',
    commission decimal(32,16) NOT NULL COMMENT '原始手续费数量',
    commission_asset varchar(20) NOT NULL COMMENT '原始手续费资产',
    realized_pnl decimal(32,16) NOT NULL COMMENT 'Binance 单笔已实现盈亏',
    trade_time datetime(3) NOT NULL COMMENT '成交时间 UTC',
    PRIMARY KEY (run_id,exchange_trade_id),
    KEY idx_quant_strategy_position_rebuild_fill_order (run_id,exchange_order_id),
    KEY idx_quant_strategy_position_rebuild_fill_page (run_id,trade_time,exchange_trade_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='影子重建逐笔成交审计事实';
