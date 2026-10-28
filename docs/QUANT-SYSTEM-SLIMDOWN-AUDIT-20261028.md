# AiQuant 量化项目瘦身审计报告（2026-10-28）

## 1. 审计范围与边界

- 仓库：`dongsilong/AiQuant`
- 审计分支：`chore/quant-system-slimdown-20261028`
- 基线：`fix/dev`
- 前端仓库：`dongsilong/AiQuant-ui`，对应分支 `chore/quant-system-slimdown-20261028`，基线 `fix/dev-ui`
- 目标：删除能够被证据确认无效的生成文件、配置、代码及数据库对象；不以减少文件数量为目标，不以“暂时没有发现引用”作为删除依据。

## 2. 已完成且已提交的清理

删除后端 `graphify-out` 中可再生成的分析缓存/图数据：
- `cache/`
- `graph.json`
- `GRAPH_TREE.html`
- `.graphify_analysis.json`
- `.graphify_detect.json`
- `manifest.json`

保留 `GRAPH_REPORT.md` 与 Graphify 标签/标记文件；新增 `.gitignore` 规则，避免上述生成物再次进入版本控制。

- 提交：[`835606a542a2b7423955851c9a2b3c8182f6482e`](https://github.com/dongsilong/AiQuant/commit/835606a542a2b7423955851c9a2b3c8182f6482e)
- 分支：[chore/quant-system-slimdown-20261028](https://github.com/dongsilong/AiQuant/tree/chore/quant-system-slimdown-20261028)
- `graphify-out` 工作树文件大小：34,089,556 → 94,574 bytes，减少 33,994,982 bytes。

## 3. 量化代码、配置与依赖审计结论

### 不应删除的关键路径

下列类别与 LIVE 订单恢复、幂等、持仓投影、对账、保护单、租约、行情/私有流、启动刷新、权限或运行时调度有关。除非存在经过测试的等价替代实现，否则保留：
- 订单派发与恢复、订单意图/接受日志、成交回放和分配、订单账本；
- 交易所账户/行情/交易适配器、REST 调度器、WebSocket 传输；
- 保护单、开平仓、策略运行时、开仓容量与入场防重；
- 账户快照、资金事件收集、交易/持仓一致性对账；
- LIVE schema 启动契约、私有流/策略运行时租约；
- 通用 RuoYi 模块、前端路由/按钮权限、动态菜单与 SQL 种子。

### `QuantCredentialUser` / `quant_credential_user`

当前分支存在 `QuantCredentialUser` 实体与 `QuantCredentialUserMapper`；凭证服务已通过 `quant_credential.user_id` 执行唯一所有者校验，提示关联表可能是旧设计，但这不足以证明全仓无引用，也不能证明已有数据库不依赖它。因此本轮没有删除实体、Mapper 或表。需要完整分支级调用图、历史数据迁移与部署兼容策略后，才可做删除决定。

### 依赖与配置

未仅凭“静态搜索没有发现引用”删除 Maven/npm 依赖或 Spring 配置。框架自动装配、反射、组件扫描、插件与运行时配置可能不体现为普通源码调用。当前环境没有可用的仓库本地构建环境，不能据此声称依赖清理已经过构建验证。

## 4. 第二轮源码瘦身（2026-10-28）

在初轮生成物清理之外，进一步执行了基于当前瘦身分支的静态源码检查，并提交以下两项低风险改动：

- `StrategyRuntimeService.java`：删除私有方法 `upsertPaperPosition(...)`（19 行方法体/注释），在该类完整源码内方法名只出现在定义处，无调用点。该方法单独创建 PAPER 持仓，而现有运行时路径通过持仓加载/同步与 `PaperTradingService` 处理 PAPER 交易；删除该未引用方法不改变现有调用路径。提交：[`0b9054c`](https://github.com/dongsilong/AiQuant/commit/0b9054c081752cb4563a5b62e151d4049f4789cf)。
- `RealtimeKlineMomentumStrategy.java`：删除仅转调 `decimal(...)` 的 `requirePositive(...)` 私有包装方法，三个参数校验点直接调用原校验函数。提交：[`5144020`](https://github.com/dongsilong/AiQuant/commit/5144020dbbda251df649b8c97c29a65577d76132)。

对 `ProtectionOrderService`、`BinanceGatewayAdapter`、`TradingReconciliationService`、`MarketStreamHub` 和 `UnifiedTradeExecutionService` 执行了私有方法引用次数扫描，未发现方法名只在定义处出现的候选项，因此没有为减少行数而删除其代码。扫描不是完整的 Java AST/字节码分析，仍需构建与测试验证。

## 5. 数据库 SQL 审计

### 指定文件 `script/sql/quant/quantdb.sql`

Git 树确认文件存在，大小 3,287,403 bytes（约 3.29 MB），不是空文件。读取其 Blob 后确认它是 2026-10-28 的 Navicat MySQL dump，包含 `CREATE TABLE` 与 `INSERT INTO` 数据，既包含量化表，也包含 RuoYi/生成器/测试树等其他表和数据。它是数据库快照/导出物，不是单纯的量化增量迁移脚本。不能在未核对初始化流程、依赖数据和部署引用的情况下删表或裁剪 INSERT 数据。

### 需要保留的基础表与增量迁移

- `ruoyi-modules/ruoyi-quant/quant_db.sql`：基础量化 schema；
- `quant_db_v6_migration.sql`、`quant_db_v7_trade_identity_migration.sql`；
- `script/sql/quant/V20261026_01__financial_event_identity_key.sql`；
- `script/sql/quant/V20261027_02__quant_order_balance_snapshot.sql`；
- `script/sql/quant/V20261028_03__quant_permission_menu_seed.sql`。

这些文件覆盖基础 schema、交易身份键、资金事件幂等、订单余额快照及权限菜单种子等不同职责，不能按文件名或内容片段相似就互相替代或删除。

**数据库删除结果：本轮没有删除任何表、字段、索引或 SQL 文件。** 目前证据不足以安全删除某张量化表；特别是 `quant_credential_user` 尚不能判定为废表。

## 6. 验证结果与限制

- 已通过 GitHub 递归文件树核验两个瘦身分支；未修改 `fix/dev` 或 `fix/dev-ui` 基线。
- 已核对后端指定 SQL dump 的真实大小与内容类型。
- 本轮仅完成静态源码检查与小范围低风险删除；未运行 Maven 编译、Java 测试、前端 pnpm build/lint 或数据库恢复演练，不能声称构建/回归/生产验证通过。
- 未创建 PR，未合并分支。
- 本报告是本轮静态审计结果，不代表已证明整个仓库所有潜在死代码均已清除。

## 7. 结论

已完成有充分证据的生成分析产物清理；未对量化交易业务逻辑、框架配置、依赖或数据库对象做猜测性删除。下一轮若要进一步减少源码和数据库体积，必须基于完整分支级符号引用分析、反射/组件扫描审查、测试与构建验证，以及 SQL dump 的初始化/恢复依赖核验后实施。
