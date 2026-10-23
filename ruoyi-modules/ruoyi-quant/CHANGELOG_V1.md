# 本次改造说明

本版本以 `ruoyi-quant_fix` 为主干，只从 `ruoyi-quant-init` 迁移 `KlineSlopeRateStrategy` 的成熟交易语义，没有迁移 ATR/Trend 等其他策略。

主要变更：

- 新增纯插件 `KlineSlopeRateStrategy`，保留实时斜率开仓、首次分批止盈、阶梯追踪止盈、本地止损语义。
- 固定硬止损改为 `quant_strategy.stop_loss_ratio`，默认 10%，不进入 params。
- `firstClosePositionRate=50%`、`firstProfitTakingRate=10%`、`localStopLossRate=5%`、`stepRatio=10%` 均支持 params。
- 实时策略判断使用 TRADE/KLINE 实时价格；MARK 不直接触发策略交易决策。
- 同 credential + symbol + kline 全局最多开仓一次，并修复 EntryGuard 主键非自增导致 raw SQL 插入失败的问题。
- Binance 侧同时维护硬止损、首次止盈/本地止损镜像、阶梯追踪保护；本地 MARKET 仅在等价交易所保护失效时 fallback，避免双平仓。
- 首次止盈后硬止损数量会按剩余仓位更新。
- 开/平仓邮件改成 `quant_notification` Outbox + 自动重试。
- Execution 状态拆成 `execution_status` / `exchange_order_status`。
- Execution/Order/Trade 增加 `strategy_id` / `strategy_instance_id` 来源归因。
- 新增 `quant_financial_event`，由 Binance Income History 同步资金费、手续费、已实现盈亏等事件。
- PAPER 凭证不再要求真实 API Key/Secret，也不调用 Binance Private API。
- 新增 `ExecutionSafetyContext`，收拢执行命令中的安全检查布尔参数。
- 提供 `quant_db_v6_migration.sql` 给旧 V5 数据库升级。

验证：

- `KlineSlopeRateStrategy` 及其信号模型已通过独立 `javac` 编译。
- 已执行策略行为 smoke test：LONG 斜率开仓、首次止盈 50%、默认 5% 本地止损、阶梯回撤、SHORT Binance 保护价格换算均通过。
- 已对全量 Java 源码执行 `javac` 语法扫描，未发现 Java 语法错误；由于上传包仅包含单模块且环境没有 Maven/父工程依赖，无法在当前环境完成完整 Maven 集成编译。
