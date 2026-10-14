# AiQuant LIVE hardening notes — 2026-10-14

> This branch is **NOT certified production-ready**. LIVE trading must remain disabled until the release gates below pass.
> Do not deploy with active positions unless position protection, execution recovery and credential migration have been validated.

## Technology rules

1. **Binance:** Use the official `io.github.binance:binance-derivatives-trading-usds-futures:12.0.3` APIs first. Existing REST order, conditional-order and account methods already use this SDK. New direct REST/WebSocket implementations are permitted only where the pinned SDK cannot provide a required capability, with an inline justification and test. Never retry an unknown-result order through an alternative client.
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
- LIVE defaults to OFF. Production strategy supervisor defaults to OFF. Production PAPER is disabled without deleting the implementation or its tests.
- Production and development credential values were replaced with environment-variable references. **Existing Git history still contains earlier values: rotate all actual exposed credentials immediately.**
- Trading terminal sends close quantity as the original decimal string, avoiding browser float rounding.

## WebSocket transport options and node-local cutover

Two independent transports are implemented under the same `ExchangeGateway` market/user-data interfaces:

| Option | Configuration | Operational trade-off |
| --- | --- | --- |
| A — official SDK | `QUANT_BINANCE_WEBSOCKET_TRANSPORT=BINANCE_SDK` | Uses the official 12.0.3 stream subscription API for market and user events. The SDK's internal queue is unbounded; the adapter monitors queue depth and disconnects at `QUANT_BINANCE_SDK_BACKLOG_LIMIT` (default 2048) for reconciliation/replay. This is a **threshold**, not a hard memory cap. Treat as opt-in pending soak/fault testing. |
| B — raw OkHttp | `QUANT_BINANCE_WEBSOCKET_TRANSPORT=RAW_OKHTTP` | Existing combined stream protocol, preserving raw-event parsing and existing stream-health/replay behavior. **Default for LIVE.** |

**Configuration at deployment:** set environment variables above, then perform a supervised rolling restart. This is safe to configure in every profile and does not modify the Binance REST SDK version.

**Controlled node-local hot switch (requires explicit RBAC grant):**

- `GET /quant/infrastructure/websocket/transport` — `quant:transport:view` permission.
- `POST /quant/infrastructure/websocket/transport?mode=BINANCE_SDK&acknowledgeReconnectionRisk=true` — `quant:transport:switch` permission. Use `mode=RAW_OKHTTP` to revert.

A switch chooses the new transport for future subscriptions, closes existing streams and explicitly announces `DISCONNECTED` to hubs so they can resubscribe and replay/reconcile. Switching is **per application node**, not a cluster-wide atomic operation; coordinate multi-node cutover. There is a reconnection window, **not zero-downtime or no-lost-events**. Do not switch with unprotected open LIVE positions or during uncertain executions. Only authorized infrastructure operators should be assigned the new permissions. Reconciliation and protective order supervision must be healthy before/after cutover.

**Important:** The SDK path uses its generated typed subscription API but consumes raw event strings from the SDK queue to preserve the already audited AiQuant event normalizer. Validate actual 12.0.3 listenKey, mark-price cadence, reconnection and event model compatibility on a staging account; the method-existence test alone does not prove end-to-end stream behavior. SDK Streams with a configured HTTP proxy intentionally fail closed until proper SDK proxy support is wired/tested.

## Architecture changes and operational validation

- Introduced `StrategyRuntimeScheduler` so supervision/reconciliation scheduling is separated from strategy decision/runtime state. The large `StrategyRuntimeService` still owns most business concerns and requires additional decomposition before high-scale operation.
- Replaced runtime's direct JDBC exposure count with `StrategyRuntimeLedgerMapper`; PAPER margin refunds belong to `PaperTradingService`.
- Execution record transitions now use guarded MyBatis UPDATE statements: delayed timeout/failed callbacks cannot overwrite CONFIRMED/RECOVERED facts. Recovery attempt increments are SQL-atomic.
- Automated MySQL 8.4/InnoDB tests validate distributed lease ownership, entry guard uniqueness, and concurrent open-capacity reservation against actual row locks. CI includes a separate ephemeral MySQL container; these tests refuse any database URL outside the dedicated `aiquant_ci_test` name.
- Before live capital, still require staged Binance endpoint tests, multi-node failure injection, stale owner fencing on the final order gateway, recovery beyond outages and 24 hours, protection-order behavior, manual intervention drills, secret rotation and explicit risk approvals.

## Required production configuration

Mandatory before booting either prod/dev profile: `QUANT_SECRET_KEY`, `MYBATIS_ENCRYPTOR_PASSWORD`, `AIQUANT_JDBC_URL`, `AIQUANT_DB_USERNAME`, `AIQUANT_DB_PASSWORD`, `AIQUANT_REDIS_PASSWORD`.

For explicit LIVE enablement: `QUANT_LIVE_ENABLED=true` and, in prod, `QUANT_RUNTIME_ENABLED=true`. These environment variables are **not** sufficient approval to deploy. Check per-account authorization, risk limits, API withdrawal prohibition, credential permissions, and platform network rules independently. No API credentials should appear in git, logs, screenshots, or monitoring payloads.

**Encryption migration warning:** Changing `QUANT_SECRET_KEY` or `MYBATIS_ENCRYPTOR_PASSWORD` without re-encrypting stored values can make existing credentials unreadable. Copy the current key into a secret manager **outside git** and coordinate a controlled key rotation with data migration. Environment-variable substitution alone does not revoke compromised credentials.

**Existing positions warning:** Do not start an upgraded node with `QUANT_RUNTIME_ENABLED=false` when it is responsible for protecting open LIVE positions. Arrange safe migration with exchange-side protection, an operator-approved cutover, and a verified new worker owner.

## Release blockers — must pass before live capital

- [ ] Run Java compilation and tagged unit/integration tests, including MySQL (not only H2) concurrent guard and capacity tests.
- [ ] Run frontend typecheck, unit tests, lint and production build.
- [ ] Verify that `StrategyEntryGuardMapper` is registered by Spring in the production profile.
- [ ] Verify credentials/secrets are rotated, database keys migrated, network access restricted, TLS verified, and all production settings externally provisioned.
- [x] Verified SDK 12.x API has `klineCandlestickStreams` and `userData` methods; added the `BinanceSdkContractTest` to enforce method presence on pinned 12.0.3 dependency.
- [ ] Existing raw OkHttp stream transport is NOT yet migrated. **Do not replace it blindly:** Binance Java Connector 12.x `StreamConnectionWrapper.subscribe()` constructs unbounded `LinkedBlockingDeque<>` queues and forwards messages using `offer`. A blocked consumer can accumulate arbitrary messages, defeating AiQuant's bounded market-stream budget. The SDK typed user-data union also needs real-world payload compatibility testing, and the SDK Jetty dependencies must be checked against the application server. A bounded adapter or upstream fix plus event-replay tests is required before switching LIVE websocket transport. This is an explicitly documented exception to SDK-first because the raw transport currently supplies safety properties the SDK wrapper does not.
- [ ] Test multi-node lease loss, fencing/stale-worker execution, concurrent strategies against the same account, and duplicate signal deliveries.
- [ ] Fault-inject order timeout, exchange accepted / local DB failed, unknown order recovery across 24+ hours, cancelled partial fills, and inconsistent order snapshots.
- [ ] Validate protection-order recovery, manual exchange trading, disconnects and stop/start while a position remains open.
- [ ] Confirm order and trade IDs can travel to/from the browser without JavaScript precision loss; close quantity hardening alone does not solve IDs.
- [ ] Approve exposure limits, emergency kill switch, monitoring/alerts, reconciliation lag SLO, DB backup/restore, and rollback/cutover runbook.
- [ ] Stage and perform a monitored minimum-size LIVE trade only after release gates pass and with explicit human authorization.

## Current limits

- Known `JdbcTemplate` call sites in the four reviewed quant runtime services (entry guard, runtime lease, capacity and PAPER settlement) have been migrated to MyBatis custom SQL. Audit other modules separately rather than assuming every JDBC operation in the whole repository has been removed.
- Maven GitHub Actions critical tests include capacity/lease/entry-guard recovery and Binance SDK API contract checks. Passing H2/Mockito tests is not a substitute for MySQL multi-node integration tests.
- Strategy runtime and terminal component decomposition, shared strategy position attribution and full OMS state machine are not yet implemented.
- The environment executing these edits cannot clone or compile GitHub repositories; source-level inspection and added tests are **not** evidence that tests passed.
