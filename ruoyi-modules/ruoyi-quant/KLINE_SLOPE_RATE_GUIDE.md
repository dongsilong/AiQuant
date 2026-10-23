# KlineSlopeRateStrategy（V1）

## 定位

`KlineSlopeRateStrategy` 是当前从 `ruoyi-quant-init` 迁移到新架构的唯一生产策略。策略插件只计算 Entry / Exit / ProtectionSignal，不直接调用 Binance、Mapper、邮件或线程池。

## 参数语义

固定硬止损不放在 `params_json` 中：

- `quant_strategy.stop_loss_ratio`：默认 `0.10`，表示杠杆后 ROI 到 `-10%` 的 Binance `STOP_MARKET` 灾难保护。

`params_json`：

```json
{
  "entryAngleDegrees": 60,
  "firstClosePositionRate": 0.50,
  "firstProfitTakingRate": 0.10,
  "localStopLossRate": 0.05,
  "stepRatio": 0.10
}
```

兼容旧字段大小写：`FirstClosePositionRate`、`FirstProfitTakingRate`、`StepRatio`；旧 `stopLossRatio` / `StopLossRatio` 仅作为本地程序止损参数的兼容别名，不作为固定硬止损来源。

## 回报率

沿用旧版期货策略语义：

`ROI = 方向价格收益率 × leverage`

因此 10x 杠杆、开仓价 100、ROI 10% 对应 LONG 触发价约 101；SHORT 对应约 99。

## 开仓

使用实时 `TRADE` 成交价（并兼容实时 KLINE close）与上一根 K 线 Open 计算斜率角度，达到 `entryAngleDegrees` 后顺势开仓。

数据库 `quant_strategy_entry_guard` 唯一键为：

`credential_id + symbol + kline_open_time`

因此同一账户、同一交易对、同一根 K 线全局最多开仓一次，即使以后有多个策略实例也不会重复抢开仓。

## 三层保护

首次止盈前同时维护：

1. `HARD_STOP`：来自 `quant_strategy.stop_loss_ratio`，默认 -10% ROI；Binance 条件单。
2. `LOCAL_STOP`：来自 `localStopLossRate`，默认 -5% ROI；Binance `STOP_MARKET` 镜像保护。
3. `FIRST_TP`：默认 +10% ROI，Binance `TAKE_PROFIT_MARKET`，数量为初始仓位的 50%。

本地 Runtime 同时计算退出条件，但只在对应 Binance 条件单缺失/异常时发送 MARKET fallback，避免交易所保护单和本地平仓“双重减仓”。

## 首次止盈后的阶梯追踪

首次止盈真实减仓确认后，撤销 `LOCAL_STOP/FIRST_TP`，保留硬止损，并建立 `STEP_TP`。

`stepRatio = 0.10` 时：

- 最高 ROI 位于第 `n` 阶：`n = floor(maxROI / stepRatio)`。
- 回撤保护 ROI：`(n - 1) × stepRatio`。
- Binance 上挂对应价格的 `STOP_MARKET`；本地实时价继续作为 fallback。

例如最高 ROI 达到 21%，则 `n=2`，保护线为 10% ROI；回撤到 10% 时平掉剩余仓位。

## 通知

开仓、主动平仓、Binance 条件单导致的部分/全部平仓都会生成邮件通知。通知先写 `quant_notification` Outbox，再尝试发送；失败进入 `RETRY`，调度器指数退避重试，不因 SMTP
短时故障丢失通知，也不会因为邮件故障回滚交易。

收件人优先级：

1. `quant_risk_config.notification_emails`
2. 外部配置 `quant.notification.default-emails`

## 数据库升级

已有 V5 数据库执行 `quant_db_v6_migration.sql`；新库直接使用 `quant_db.sql`。
