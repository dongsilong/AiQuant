-- ruoyi-quant V6 架构迁移：KlineSlopeRateStrategy + 执行状态拆分 + 策略归因 + 财务事件
-- 执行前请先备份数据库；本脚本基于 ruoyi-quant_fix 原 V5 表结构。

ALTER TABLE `quant_notification`
    ADD KEY `idx_quant_notification_retry` (`channel`,`status`,`next_retry_at`);

ALTER TABLE `quant_credential`
    MODIFY COLUMN `api_key` varchar (128) NULL COMMENT 'API Key；PAPER 可为空',
    MODIFY COLUMN `secret_key` varchar (512) NULL COMMENT 'Secret Key；PAPER 可为空',
    MODIFY COLUMN `signature_type` varchar (20) NULL COMMENT '签名类型；PAPER 可为空';

ALTER TABLE `quant_strategy`
    ADD COLUMN `stop_loss_ratio` decimal(12, 6) NOT NULL DEFAULT 0.100000 COMMENT '策略固定硬止损回报率；KlineSlopeRateStrategy 不从 params 读取' AFTER `default_stop_loss_ratio`;

ALTER TABLE `quant_strategy_entry_guard`
    MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
DROP INDEX `uk_quant_strategy_entry`,
  ADD UNIQUE KEY `uk_quant_strategy_entry` (`credential_id`,`symbol`,`kline_open_time`);

ALTER TABLE `quant_order`
    ADD COLUMN `strategy_id` bigint NULL COMMENT '来源策略ID，外部/手工订单可为空' AFTER `credential_id`,
  ADD COLUMN `strategy_instance_id` bigint NULL COMMENT '来源策略实例ID，外部/手工订单可为空' AFTER `strategy_id`,
  ADD KEY `idx_quant_order_strategy_instance` (`strategy_instance_id`,`created_at`);

ALTER TABLE `quant_trade`
    ADD COLUMN `strategy_id` bigint NULL COMMENT '来源策略ID，外部/手工成交可为空' AFTER `credential_id`,
  ADD COLUMN `strategy_instance_id` bigint NULL COMMENT '来源策略实例ID，外部/手工成交可为空' AFTER `strategy_id`,
  ADD KEY `idx_quant_trade_strategy_instance` (`strategy_instance_id`,`trade_time`);

ALTER TABLE `quant_execution_record`
    ADD COLUMN `strategy_id` bigint NULL COMMENT '来源策略ID，手工执行可为空' AFTER `credential_id`,
  ADD COLUMN `strategy_instance_id` bigint NULL COMMENT '来源策略实例ID，手工执行可为空' AFTER `strategy_id`,
  CHANGE COLUMN `status` `execution_status` varchar(20) NOT NULL COMMENT 'PENDING/CONFIRMED/UNKNOWN/FAILED/RECOVERED',
  ADD COLUMN `exchange_order_status` varchar(30) NULL COMMENT '交易所订单状态' AFTER `execution_status`,
DROP INDEX `idx_quant_execution_status`,
DROP INDEX `idx_quant_execution_pending`,
  ADD KEY `idx_quant_execution_status` (`execution_status`,`updated_at`),
  ADD KEY `idx_quant_execution_pending` (`execution_status`,`created_at`),
  ADD KEY `idx_quant_execution_strategy_instance` (`strategy_instance_id`,`created_at`);

CREATE TABLE IF NOT EXISTS `quant_financial_event`
(
    `id`
    bigint
    NOT
    NULL
    COMMENT
    '主键ID',
    `credential_id`
    bigint
    NOT
    NULL
    COMMENT
    '量化凭证ID',
    `strategy_id`
    bigint
    NULL
    COMMENT
    '来源策略ID',
    `strategy_instance_id`
    bigint
    NULL
    COMMENT
    '来源策略实例ID',
    `symbol`
    varchar
(
    32
) NULL COMMENT '交易对',
    `event_type` varchar
(
    40
) NOT NULL COMMENT 'REALIZED_PNL/COMMISSION/FUNDING_FEE/REBATE/TRANSFER/LIQUIDATION等',
    `amount` decimal
(
    32,
    16
) NOT NULL DEFAULT 0 COMMENT '金额',
    `asset` varchar
(
    20
) NULL COMMENT '资产',
    `exchange_transaction_id` bigint NOT NULL COMMENT '交易所资金流水ID',
    `info` varchar
(
    500
) NULL COMMENT '附加信息',
    `event_time` datetime NOT NULL COMMENT '事件时间',
    `created_at` datetime NOT NULL COMMENT '创建时间',
    PRIMARY KEY
(
    `id`
),
    UNIQUE KEY `uk_quant_financial_event`
(
    `credential_id`,
    `exchange_transaction_id`
),
    KEY `idx_quant_financial_event_time`
(
    `credential_id`,
    `event_time`
),
    KEY `idx_quant_financial_event_strategy`
(
    `strategy_instance_id`,
    `event_time`
)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE =utf8mb4_general_ci COMMENT='交易财务事件账本';
