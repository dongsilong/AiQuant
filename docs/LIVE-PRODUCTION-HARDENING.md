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
- Execution recovery no longer drops unresolved records solely because they are older than 24 hours. Query order favors least-recently-checked records; fallback order matches use actual exchange status.
- Binance SDK authentication adapter no longer imports OkHttp's private/internal `HttpMethod` class.
- LIVE defaults to OFF. Production strategy supervisor defaults to OFF. Production PAPER is disabled without deleting the implementation or its tests.
- Production and development credential values were replaced with environment-variable references. **Existing Git history still contains earlier values: rotate all actual exposed credentials immediately.**
- Trading terminal sends close quantity as the original decimal string, avoiding browser float rounding.

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
- [ ] Verify Binance SDK 12.0.3 method coverage for WebSocket Streams and user-data stream; raw OkHttp socket remains and is NOT yet replaced.
- [ ] Test multi-node lease loss, fencing/stale-worker execution, concurrent strategies against the same account, and duplicate signal deliveries.
- [ ] Fault-inject order timeout, exchange accepted / local DB failed, unknown order recovery across 24+ hours, cancelled partial fills, and inconsistent order snapshots.
- [ ] Validate protection-order recovery, manual exchange trading, disconnects and stop/start while a position remains open.
- [ ] Confirm order and trade IDs can travel to/from the browser without JavaScript precision loss; close quantity hardening alone does not solve IDs.
- [ ] Approve exposure limits, emergency kill switch, monitoring/alerts, reconciliation lag SLO, DB backup/restore, and rollback/cutover runbook.
- [ ] Stage and perform a monitored minimum-size LIVE trade only after release gates pass and with explicit human authorization.

## Current limits

- MyBatis migration is incremental: capacity and lease JDBC operations remain until equivalence and transaction tests are available.
- Strategy runtime and terminal component decomposition, shared strategy position attribution and full OMS state machine are not yet implemented.
- The environment executing these edits cannot clone or compile GitHub repositories; source-level inspection and added tests are **not** evidence that tests passed.
