-- V20261103_01: keep strategy commission amounts in their original assets.
-- Apply after V20261102_01__strategy_position_projection.sql.
-- Existing fill-event commission facts remain the source for historical backfill;
-- this table is populated transactionally for newly projected fills and later by controlled replay.

CREATE TABLE IF NOT EXISTS quant_strategy_position_fee_event (
    id bigint NOT NULL COMMENT '主键',
    credential_id bigint NOT NULL COMMENT '量化凭证 ID',
    symbol varchar(32) NOT NULL COMMENT '交易对',
    strategy_id bigint NOT NULL COMMENT '策略 ID',
    strategy_instance_id bigint NOT NULL COMMENT '策略实例 ID',
    cycle_id bigint NOT NULL COMMENT '持仓周期 ID',
    exchange_trade_id bigint NOT NULL COMMENT 'Binance 成交 ID',
    exchange_order_id bigint NOT NULL COMMENT 'Binance 订单 ID',
    position_side varchar(8) NOT NULL COMMENT '策略持仓方向 LONG/SHORT',
    commission decimal(32,16) NOT NULL COMMENT '手续费数量，不跨资产合并',
    commission_asset varchar(20) NOT NULL COMMENT '手续费资产',
    trade_time datetime(3) NOT NULL COMMENT '成交时间（UTC）',
    created_at datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_quant_strategy_position_fee_event (credential_id, symbol, exchange_trade_id),
    KEY idx_quant_strategy_position_fee_asset (credential_id, strategy_instance_id, symbol, commission_asset, trade_time),
    KEY idx_quant_strategy_position_fee_cycle (cycle_id, commission_asset)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='策略成交手续费资产分账事件';
