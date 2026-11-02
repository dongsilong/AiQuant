-- V20261102_01: durable per-strategy position inventory and immutable fill projection.
-- Run against the configured quant database after a verified backup.
-- Existing fills are intentionally not auto-backfilled: only exact immutable order intents
-- can establish OPEN/CLOSE ownership. Rebuild historical inventory from verified fills
-- before using aggregate strategy inventory as a release/reconciliation baseline.

CREATE TABLE IF NOT EXISTS quant_strategy_position_inventory (
    id bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
    credential_id bigint NOT NULL COMMENT '量化凭证 ID',
    symbol varchar(32) NOT NULL COMMENT '交易对',
    strategy_instance_id bigint NOT NULL COMMENT '策略实例 ID',
    position_side varchar(8) NOT NULL COMMENT '策略持仓方向 LONG/SHORT',
    strategy_id bigint NOT NULL COMMENT '策略 ID',
    open_quantity decimal(32,16) NOT NULL DEFAULT 0 COMMENT '策略当前未平仓数量',
    cost_basis decimal(32,16) NOT NULL DEFAULT 0 COMMENT '策略当前剩余持仓成本',
    realized_pnl decimal(32,16) NOT NULL DEFAULT 0 COMMENT 'Binance 已实现盈亏累计',
    commissions decimal(32,16) NOT NULL DEFAULT 0 COMMENT '成交手续费累计',
    commission_asset varchar(20) DEFAULT NULL COMMENT '手续费资产；不同资产不得混合',
    cycle_number bigint NOT NULL DEFAULT 0 COMMENT '持仓周期序号',
    cycle_id bigint DEFAULT NULL COMMENT '当前未平仓周期 ID',
    last_trade_time datetime(3) DEFAULT NULL COMMENT '最后应用成交时间（UTC）',
    last_trade_id bigint DEFAULT NULL COMMENT '最后应用 Binance 成交 ID',
    updated_at datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_quant_strategy_position_inventory (credential_id, symbol, strategy_instance_id, position_side),
    KEY idx_quant_strategy_position_inventory_owner (credential_id, symbol, position_side, strategy_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='策略级持仓库存投影';

CREATE TABLE IF NOT EXISTS quant_strategy_position_cycle (
    id bigint NOT NULL COMMENT '持仓周期 ID',
    credential_id bigint NOT NULL COMMENT '量化凭证 ID',
    symbol varchar(32) NOT NULL COMMENT '交易对',
    strategy_instance_id bigint NOT NULL COMMENT '策略实例 ID',
    position_side varchar(8) NOT NULL COMMENT '策略持仓方向 LONG/SHORT',
    cycle_number bigint NOT NULL COMMENT '策略实例内持仓周期序号',
    opened_at datetime(3) NOT NULL COMMENT '周期开始时间（UTC）',
    closed_at datetime(3) DEFAULT NULL COMMENT '周期结束时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_quant_strategy_position_cycle (credential_id, symbol, strategy_instance_id, position_side, cycle_number),
    KEY idx_quant_strategy_position_cycle_open (credential_id, symbol, position_side, closed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='策略持仓周期';

CREATE TABLE IF NOT EXISTS quant_strategy_position_fill_event (
    id bigint NOT NULL COMMENT '主键',
    credential_id bigint NOT NULL COMMENT '量化凭证 ID',
    symbol varchar(32) NOT NULL COMMENT '交易对',
    strategy_id bigint NOT NULL COMMENT '策略 ID',
    strategy_instance_id bigint NOT NULL COMMENT '策略实例 ID',
    cycle_id bigint NOT NULL COMMENT '持仓周期 ID',
    exchange_trade_id bigint NOT NULL COMMENT 'Binance 成交 ID',
    exchange_order_id bigint NOT NULL COMMENT 'Binance 订单 ID',
    position_side varchar(8) NOT NULL COMMENT '策略持仓方向 LONG/SHORT',
    fill_role varchar(8) NOT NULL COMMENT '成交角色 OPEN/CLOSE',
    exchange_side varchar(4) NOT NULL COMMENT '交易所买卖方向 BUY/SELL',
    quantity decimal(32,16) NOT NULL COMMENT '成交数量',
    fill_price decimal(32,16) NOT NULL COMMENT '成交价格',
    commission decimal(32,16) NOT NULL COMMENT '手续费数量',
    commission_asset varchar(20) NOT NULL COMMENT '手续费资产',
    realized_pnl decimal(32,16) NOT NULL COMMENT 'Binance 已实现盈亏',
    trade_time datetime(3) NOT NULL COMMENT '成交时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_quant_strategy_position_fill_event (credential_id, symbol, exchange_trade_id),
    KEY idx_quant_strategy_position_fill_order (credential_id, symbol, exchange_order_id),
    KEY idx_quant_strategy_position_fill_cycle (cycle_id, trade_time, exchange_trade_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='策略不可变成交投影事件';
