-- 双开轮次级协调事实：一轮对应一根入场K线，持仓腿仍由 quant_strategy_position_state 管理。
CREATE TABLE IF NOT EXISTS quant_strategy_hedge_round (
    id bigint NOT NULL COMMENT '主键',
    hedge_round_key varchar(36) NOT NULL COMMENT '双开轮次唯一标识',
    strategy_id bigint NOT NULL COMMENT '策略主键',
    instance_id bigint NOT NULL COMMENT '策略实例主键',
    credential_id bigint NOT NULL COMMENT '账户凭证主键',
    symbol varchar(64) NOT NULL COMMENT '交易对',
    entry_kline_open_time datetime NOT NULL COMMENT '触发入场的15m K线开盘时间',
    status varchar(24) NOT NULL COMMENT '轮次状态：OPENING/DEGRADED/OPEN/CLOSING/CLOSED/ABORTED',
    entry_angle_degrees decimal(16,8) DEFAULT NULL COMMENT '入场斜率角度快照',
    entry_equity_snapshot decimal(32,16) NOT NULL COMMENT '双腿共用的开仓前账户总权益',
    margin_ratio decimal(20,12) DEFAULT NULL COMMENT '每条腿保证金占权益比例',
    leverage int DEFAULT NULL COMMENT '开仓杠杆快照',
    target_margin_per_leg decimal(32,16) DEFAULT NULL COMMENT '每条腿目标保证金',
    target_notional_per_leg decimal(32,16) DEFAULT NULL COMMENT '每条腿目标名义金额',
    strategy_params_snapshot text DEFAULT NULL COMMENT '本轮有效策略参数快照',
    long_leg_status varchar(24) NOT NULL DEFAULT 'NOT_STARTED' COMMENT 'LONG腿执行状态',
    short_leg_status varchar(24) NOT NULL DEFAULT 'NOT_STARTED' COMMENT 'SHORT腿执行状态',
    long_idempotency_key varchar(128) DEFAULT NULL COMMENT 'LONG腿执行幂等键',
    short_idempotency_key varchar(128) DEFAULT NULL COMMENT 'SHORT腿执行幂等键',
    long_client_order_id varchar(64) DEFAULT NULL COMMENT 'LONG腿客户端订单号',
    short_client_order_id varchar(64) DEFAULT NULL COMMENT 'SHORT腿客户端订单号',
    long_order_id bigint DEFAULT NULL COMMENT 'LONG腿交易所订单号',
    short_order_id bigint DEFAULT NULL COMMENT 'SHORT腿交易所订单号',
    long_position_id bigint DEFAULT NULL COMMENT 'LONG腿本地持仓主键',
    short_position_id bigint DEFAULT NULL COMMENT 'SHORT腿本地持仓主键',
    active_scope_key varchar(160) GENERATED ALWAYS AS (
        CASE WHEN status NOT IN ('CLOSED', 'ABORTED')
             THEN CONCAT(instance_id, ':', UPPER(symbol))
             ELSE NULL END
    ) STORED COMMENT '同一实例同一交易对仅允许一轮未结束双开',
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    version int NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_quant_hedge_round_key (hedge_round_key),
    UNIQUE KEY uk_quant_hedge_round_instance_symbol_kline (instance_id, symbol, entry_kline_open_time),
    UNIQUE KEY uk_quant_hedge_round_active_scope (active_scope_key),
    KEY idx_quant_hedge_round_credential_symbol (credential_id, symbol, status),
    KEY idx_quant_hedge_round_updated_at (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='双开策略轮次协调事实表';
