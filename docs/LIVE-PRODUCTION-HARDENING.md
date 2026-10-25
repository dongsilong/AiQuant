# AiQuant LIVE hardening notes — 2026-10-14

> This branch is **NOT certified production-ready**. Per operator request, the `quant.live.enabled` **configuration defaults to `true`**. Keep `QUANT_LIVE_ENABLED=false` in any environment that has not passed the release gates below; production strategy supervisor remains independently disabled by default.
> Do not deploy with active positions unless position protection, execution recovery and credential migration have been validated.

## Technology rules

1. **Binance:** Use the official `io.github.binance:binance-derivatives-trading-usds-futures:12.1.0` APIs first. Existing REST order, conditional-order and account methods already use this SDK. New direct REST/WebSocket implementations are permitted only where the pinned SDK cannot provide a required capability, with an inline justification and test. Never retry an unknown-result order through an alternative client.
2. **Persistence:** Use MyBatis-Plus CRUD/Wrapper for simple queries; use MyBatis Mapper custom SQL for atomic updates, `FOR UPDATE`, `INSERT IGNORE`, JOINs and lease ownership. Preserve affected-row checks, unique constraints and Spring transaction semantics when migrating legacy `JdbcTemplate` code. `TransactionTemplate(REQUIRES_NEW, READ_COMMITTED)` used for short reservations must not be replaced with unprotected read-modify-write code.
3. **Separation:** Business modules call `ExchangeGateway`, not Binance SDK classes directly. Management API must not become the single source of trading execution state.
4. **Real positions:** Exchange position, strategy allocation and pending capacity reservation are different concepts. No live rollout to shared-account multi-strategy operation until attribution and reconciliation tests are reviewed.

## Changes in this branch

- Atomic candle entry guard SQL migrated from `JdbcTemplate` to `StrategyEntryGuardMapper`, retaining database unique-key and conditional write semantics.
- `StrategyRuntimeLeaseService`, `OpenCapacityService`, and `PaperTradingService` migrated to MyBatis custom atomic mappers. Lease and capacity use 3-second per-statement timeouts; capacity preserves `TransactionTemplate(REQUIRES_NEW, READ_COMMITTED)` and the account `FOR UPDATE` lock. PAPER balance and execution records retain transaction-scoped row locks and atomic amount updates.
- Corrected local signed one-way `BOTH` short positions being excluded by `quantity>0` during capacity checks.
- Execution recovery no longer drops unresolved records solely because they are older than 24 hours. Query order favors least-recently-checked records; fallback order matches use actual exchange status.
- Binance SDK authentication adapter no longer imports OkHttp's private/internal `HttpMethod` class.
- Binance LIVE connectivity now rejects `canWithdraw=true` and missing/unknown withdrawal status (and rejects non-tradable credentials); the previous commented-out security check plus unconditional `withdrawalDisabled=true` success report was unsafe. Added permission regression tests. Account/API-key privileges still require independent operational verification.
- LIVE global configuration defaults to **ON** (`QUANT_LIVE_ENABLED:true`), with explicit `QUANT_LIVE_ENABLED=false` override. The production strategy supervisor still defaults to **OFF** (`QUANT_RUNTIME_ENABLED:false`). Production PAPER remains disabled.
- Production and development credential values were replaced with environment-variable references. **Existing Git history still contains earlier values: rotate all actual exposed credentials immediately.**
- Trading terminal sends close quantity as the original decimal string, avoiding browser float rounding.

## 2026-10-14 next-round fixes

- Removed committed SMTP credential literals from both dev/prod application profiles. Mail is disabled by default and requires `AIQUANT_MAIL_*` deployment secrets. Shared MQTT credentials also moved to `AIQUANT_MQTT_*` environment variables. CI now runs `script/security/check_quant_config_secrets.py` to prevent plaintext regressions in these known fields. **Rotate previously exposed real secrets** and review historical commits; editing tracked files does not revoke credentials.
- Fixed CANCEL/FILL recovery overwrite: `ExecutionRecoveryService` no longer invokes unguarded `updateById` on a stale `QuantExecutionRecord`. Recovery annotations use conditional MyBatis updates scoped to the exact recovered order ID/status.
- Exchange `Long` order/trade/algo IDs in key REST/SSE DTOs and execution results now serialize as **JSON strings** (with an explicit regression test). Frontend SSE parsing preserves `i`, `t`, and `aid` integer literals before `JSON.parse`, and rejects already-rounded unsafe `number` IDs. Other untyped/map-based APIs still require an audit.
- Migrated `ProtectionTargetCoordinator` atomic lease/intent SQL to `ProtectionTargetMapper`, retaining the short `REQUIRES_NEW` transaction, `FOR UPDATE` and conditional owner/lease guards. Existing H2 coordinator tests now execute via MyBatis-Spring.
- Added `script/sql/quant/production-preflight.sql`: read-only MySQL table/unique-index verification. **A reviewed canonical production DDL and numbered migrations are still missing.** Do not copy the temporary CI schema to LIVE.

## WebSocket transport options and node-local cutover

Two independent transports are implemented under the same `ExchangeGateway` market/user-data interfaces:

| Option | Configuration | Operational trade-off |
| --- | --- | --- |
| A — official SDK | `QUANT_BINANCE_WEBSOCKET_TRANSPORT=BINANCE_SDK` | Uses the official 12.1.0 stream subscription API for market and user events. The SDK's internal queue is unbounded; the adapter monitors queue depth and disconnects at `QUANT_BINANCE_SDK_BACKLOG_LIMIT` (default 2048) for reconciliation/replay. This is a **threshold**, not a hard memory cap. Treat as opt-in pending soak/fault testing. |
| B — raw OkHttp | `QUANT_BINANCE_WEBSOCKET_TRANSPORT=RAW_OKHTTP` | Existing combined stream protocol, preserving raw-event parsing and existing stream-health/replay behavior. **Default in dev/prod profiles and LIVE.** |

**Configuration at deployment:** set environment variables above, then perform a supervised rolling restart. This is safe to configure in every profile and does not modify the Binance REST SDK version.

**Controlled node-local hot switch (requires explicit RBAC grant):**

- `GET /quant/infrastructure/websocket/transport` — `quant:transport:view` permission.
- `POST /quant/infrastructure/websocket/transport?mode=BINANCE_SDK&acknowledgeReconnectionRisk=true` — `quant:transport:switch` permission. Use `mode=RAW_OKHTTP` to revert.

A switch chooses the new transport for future subscriptions, closes existing streams and explicitly announces `DISCONNECTED` to hubs so they can resubscribe and replay/reconcile. Switching is **per application node**, not a cluster-wide atomic operation; coordinate multi-node cutover. There is a reconnection window, **not zero-downtime or no-lost-events**. Do not switch with unprotected open LIVE positions or during uncertain executions. Only authorized infrastructure operators should be assigned the new permissions. Reconciliation and protective order supervision must be healthy before/after cutover.

**Important:** The SDK path uses its generated typed subscription API but consumes raw event strings from the SDK queue to preserve the already audited AiQuant event normalizer. Validate actual 12.1.0 listenKey, mark-price cadence, reconnection and event model compatibility on a staging account; the method-existence test alone does not prove end-to-end stream behavior. SDK Streams with a configured HTTP proxy intentionally fail closed until proper SDK proxy support is wired/tested.

## Architecture changes and operational validation

- Introduced `StrategyRuntimeScheduler` so supervision/reconciliation scheduling is separated from strategy decision/runtime state. The large `StrategyRuntimeService` still owns most business concerns and requires additional decomposition before high-scale operation.
- Replaced runtime's direct JDBC exposure count with `StrategyRuntimeLedgerMapper`; PAPER margin refunds belong to `PaperTradingService`.
- Execution record transitions now use guarded MyBatis UPDATE statements: delayed timeout/failed callbacks cannot overwrite CONFIRMED/RECOVERED facts. Recovery attempt increments are SQL-atomic.
- Automated MySQL 8.4/InnoDB tests validate distributed lease ownership, entry guard uniqueness, and concurrent open-capacity reservation against actual row locks. CI includes a separate ephemeral MySQL container; these tests refuse any database URL outside the dedicated `aiquant_ci_test` name.
- Before live capital, still require staged Binance endpoint tests, multi-node failure injection, stale owner fencing on the final order gateway, recovery beyond outages and 24 hours, protection-order behavior, manual intervention drills, secret rotation and explicit risk approvals.

## Required production configuration

Mandatory before booting either prod/dev profile: `QUANT_SECURITY_SECRET_KEY`, `AIQUANT_ENCRYPTOR_PASSWORD`, `AIQUANT_DATASOURCE_PASSWORD`, `AIQUANT_REDIS_PASSWORD`, `AIQUANT_SA_TOKEN_JWT_SECRET`, and `AIQUANT_API_DECRYPT_PRIVATE_KEY`. Production additionally requires `AIQUANT_DATASOURCE_URL`, `AIQUANT_DATASOURCE_USERNAME`, `AIQUANT_REDIS_HOST`, and `AIQUANT_REDIS_PORT`; the production profile deliberately has no localhost fallback for database/Redis endpoints. Development retains local-only datasource defaults.

LIVE global permission is now enabled by default. For active strategy execution in prod, `QUANT_RUNTIME_ENABLED=true` remains separately required. Set `QUANT_LIVE_ENABLED=false` wherever real trading has not been approved. These environment variables are **not** sufficient approval to deploy. Check per-account authorization, risk limits, API withdrawal prohibition, credential permissions, and platform network rules independently. No API credentials should appear in git, logs, screenshots, or monitoring payloads.

**Encryption migration warning:** Changing `QUANT_SECURITY_SECRET_KEY` or `AIQUANT_ENCRYPTOR_PASSWORD` without re-encrypting stored values can make existing credentials unreadable. Copy the current key into a secret manager **outside git** and coordinate a controlled key rotation with data migration. Environment-variable substitution alone does not revoke compromised credentials.

**Existing positions warning:** Do not start an upgraded node with `QUANT_RUNTIME_ENABLED=false` when it is responsible for protecting open LIVE positions. Arrange safe migration with exchange-side protection, an operator-approved cutover, and a verified new worker owner.

## Release blockers — must pass before live capital

- [ ] Run Java compilation and tagged unit/integration tests, including MySQL (not only H2) concurrent guard and capacity tests.
- [ ] Run frontend typecheck, unit tests, lint and production build.
- [ ] Verify that `StrategyEntryGuardMapper` is registered by Spring in the production profile.
- [ ] Verify credentials/secrets are rotated, database keys migrated, network access restricted, TLS verified, and all production settings externally provisioned.
- [x] Verified SDK 12.x API has `klineCandlestickStreams` and `userData` methods; added the `BinanceSdkContractTest` to enforce method presence on pinned 12.1.0 dependency.
- [ ] Validate the new optional SDK transport in long-running LIVE-like staging, including real user-data payloads, stream backlog storms, Jetty dependency compatibility, disconnect-replay and stream cutover with open positions. The raw transport remains the default and rollback option until this passes; SDK transport currently monitors a **soft** backlog threshold, not a physical bound.
- [ ] Test multi-node lease loss, fencing/stale-worker execution, concurrent strategies against the same account, and duplicate signal deliveries.
- [ ] Fault-inject order timeout, exchange accepted / local DB failed, unknown order recovery across 24+ hours, cancelled partial fills, and inconsistent order snapshots.
- [ ] Validate protection-order recovery, manual exchange trading, disconnects and stop/start while a position remains open.
- [ ] Confirm order and trade IDs can travel to/from the browser without JavaScript precision loss; close quantity hardening alone does not solve IDs.
- [ ] Approve exposure limits, emergency kill switch, monitoring/alerts, reconciliation lag SLO, DB backup/restore, and rollback/cutover runbook.
- [ ] Stage and perform a monitored minimum-size LIVE trade only after release gates pass and with explicit human authorization.

## Current limits

- Known `JdbcTemplate` call sites in the four reviewed quant runtime services (entry guard, runtime lease, capacity and PAPER settlement) have been migrated to MyBatis custom SQL. Audit other modules separately rather than assuming every JDBC operation in the whole repository has been removed.
- Maven GitHub Actions now run critical invariants, the complete `@Tag("dev")` quant regression suite, and a separate MySQL/InnoDB concurrency job for atomic lease/guard/capacity semantics. These checks do not substitute for independent multi-node fault injection against real Binance services.
- Strategy runtime and terminal component decomposition, shared strategy position attribution and full OMS state machine are not yet implemented.
- The environment executing these edits cannot clone or compile GitHub repositories; source-level inspection and added tests are **not** evidence that tests passed.


## 2026-10-25 — environment and schema gates

### Runtime switches

- `QUANT_LIVE_ENABLED` defaults to `true` as explicitly requested. Set it to `false` on every node until the target database and exchange acceptance gates are satisfied.
- `QUANT_RUNTIME_ENABLED` defaults to `true` in the development profile and `false` in the production profile. Production strategy execution therefore remains independently disabled until an operator explicitly enables it.
- `QUANT_BINANCE_WEBSOCKET_TRANSPORT` selects `BINANCE_SDK` or `RAW_OKHTTP`; `QUANT_BINANCE_SDK_BACKLOG_LIMIT` controls the SDK backlog disconnect threshold. Threshold-based disconnect is not a hard memory cap.

### Secret injection

Do not store production values in tracked YAML. Configure these variables through the deployment secret manager (only configure integrations actually enabled in that environment):

- Core security: `QUANT_SECURITY_SECRET_KEY`, `AIQUANT_SA_TOKEN_JWT_SECRET`, `AIQUANT_API_DECRYPT_PRIVATE_KEY`, `AIQUANT_ENCRYPTOR_PASSWORD`.
- Infrastructure: `AIQUANT_DATASOURCE_PASSWORD`, `AIQUANT_REDIS_PASSWORD`, `AIQUANT_MONITOR_INSTANCE_PASSWORD`, `AIQUANT_MONITOR_PASSWORD`.
- Optional mail/MQTT: `AIQUANT_MAIL_ENABLED`, `AIQUANT_MAIL_FROM`, `AIQUANT_MAIL_USER`, `AIQUANT_MAIL_PASSWORD`, `AIQUANT_MQTT_USERNAME`, `AIQUANT_MQTT_PASSWORD`.
- Optional integrations: `AIQUANT_SNAIL_JOB_TOKEN`, `AIQUANT_SNAIL_AI_TOKEN`, and `AIQUANT_JUSTAUTH_<PROVIDER>_CLIENT_SECRET` (provider name is uppercase, e.g. `GITEE`, `MAXKEY`, `TOPIAM`).

Empty defaults prevent source-embedded secrets but do not configure an integration. Verify each enabled feature's required credentials before rollout. Credentials and private keys that were previously committed must be rotated; changing current files does not revoke values in Git history.

### Schema review boundaries

`script/sql/quant/production-preflight.sql` requires schema used by the active LIVE dispatch, recovery and cross-node snapshot-fencing paths. The strategy position-cycle/fill-event/inventory tables from migrations V07/V08 are deliberately excluded from mandatory preflight because those migrations are marked review-only and the projector is not automatically wired. Do not install or enable that projector until its provenance, historical replay, side/role attribution, commission accounting and reconciliation contracts have passed an independent staging review.

The current preflight is a read-only contract check, not a canonical production DDL baseline. First-time financial-income reconciliation now bootstraps up to 180 days of Binance income history rather than treating the ordinary 48-hour scheduler window as complete; the durable cursor advances only with the transaction that persists those financial facts. The recovery case/audit migration V05 is also marked design-only and requires a reviewed production DDL, backup/restore rehearsal, least-privilege database grants and external immutable audit retention before use.


### 2026-10-25 — fill-ledger immutability and schema alignment

- The read-only production preflight and LIVE startup guard now agree on required tables and unique-key contracts, including the raw Binance fill journal, monotonic fill-history cursor and active recovery-case uniqueness. The guard reports the actual reviewed migration source for the fill journal rather than a nonexistent path.
- `V20261025_01__strategy_fill_journal.sql` documents the journal/cursor DDL as review-only. It is not an auto-migration and must be rehearsed on a restored production clone before deployment.
- The collector requires stable trade/time/symbol identity and complete pagination, but preserves rows whose order/side/quantity/price/commission/fee asset/realized-PnL fields are incomplete. LIVE writes those raw facts to the fill journal and leaves them unresolved; it does not fabricate a numeric `quant_trade` row or coerce unknown money fields to zero. Non-LIVE reconciliation fails on incomplete numeric trade facts.
- Replaying an already persisted `quant_trade` now verifies immutable Binance facts before any write. A conflict fails reconciliation; it cannot overwrite the original price/quantity/commission/PnL. Strategy attribution can only be enriched from the exact local order when ownership fields are absent, and conflicting non-null ownership is rejected. Existing funding-fee values are not reset by a trade-history replay.
- A previously committed private key embedded in an `application.yml` comment has been removed from the current branch. This does not erase Git history; rotate that key if it was ever used outside a disposable environment.


### 2026-10-25 — complete income/funding history

- `StrictBinanceIncomeCollector` now splits saturated 1000-row Binance income-history windows, rejects missing financial event identity/amount/asset/time, and treats conflicting repeated `(incomeType, tranId)` facts as a hard error. Distinct income types may legally share the same transaction ID.
- Financial income history now uses a durable per-credential complete-window watermark. It advances in the same transaction as financial event persistence, catches up from the stored checkpoint after downtime, processes a backlog in bounded 180-day chunks, and refuses a future checkpoint rather than silently skipping events.
- The read-only production preflight and LIVE startup guard require `quant_financial_event` with its `(credential_id, exchange_transaction_id, event_type)` unique identity and `quant_financial_event_sync_cursor` with its primary key. Reviewed migration sources are `V20261024_01__financial_event_income_type_identity.sql` and `V20261025_02__financial_event_sync_cursor.sql`.
