# ruoyi-quant V1 架构基线

## 主链路

```text
MarketStreamHub
  -> StrategyRuntimeService (编排)
  -> StrategyPlugin (纯信号)
  -> RiskDecision / LiveTradingGate
  -> UnifiedTradeExecutionService
  -> ExecutionRecord (幂等 + UNKNOWN Recovery)
  -> Exchange Port / BinanceGatewayAdapter
```

交易所事实通过 `UserData + REST Reconciliation` 回写 `Order / Trade / Position / Protection / FinancialEvent`。

## 本次冻结的关键边界

- `KlineSlopeRateStrategy` 不允许直接依赖 Binance SDK、Mapper、邮件、线程池。
- Binance SDK 继续只存在于 `exchange/binance` 适配层。
- `execution_status` 与 `exchange_order_status` 分离。
- `quant_execution_record / quant_order / quant_trade` 保留 `strategy_id + strategy_instance_id` 来源归因；外部/手工交易允许为空。
- 资金费、手续费、已实现盈亏等交易所 Income History 进入 `quant_financial_event`，不再把 Funding 强塞进成交事实。
- PAPER 不要求真实 API Key/Secret，也不访问 Binance Private API。
- 当前物理仓位所有权继续是 `credential + symbol`；暂不引入同 symbol 多策略虚拟分仓，也暂不扩 Hedge Mode 多方向 Runtime Owner。
- `ExecutionSafetyContext` 聚合执行安全检查，避免命令对象继续膨胀 boolean 参数。
- Runtime 当前采用渐进拆分策略：新增复杂逻辑下沉到 Execution / Protection / Reconcile / Notification 等服务，不再继续把交易所细节塞回 Strategy。

## 后续新增策略规则

以后新增策略实现 `StrategyPlugin`，只能输出：

- `EntrySignal`
- `ExitSignal`
- 可选 `ProtectionSignal`

公共能力必须复用 Runtime、Risk、Execution、Protection、Reconciliation、Notification，不允许每个策略自行创建 Binance Client、WebSocket、Scheduler、订单恢复或邮件发送。
