# LIVE production release gates — code repairs vs external acceptance evidence

The `hardening/live-production-20261014` branch is a work branch, **not** a
certification that real-funds trading can be turned on safely.

## Completed in code and covered by GitHub CI

- The normal LIVE CANCEL path resolves the authentic client order ID if only
  an exchange order ID was supplied. A successful HTTP cancel response does
  not authorize further orders: the service must fetch the exact terminal
  Binance fact, persist it, then release the durable account-symbol guard.
  `PARTIALLY_FILLED` is not considered terminal.
- OMS recovery does not treat an active partial fill as final cancellation.
  Exact terminal `FILLED`, `CANCELED`, `EXPIRED` or `REJECTED` facts are
  required before the unresolved cancel ticket can be cleared.
- LIVE strategy orders recheck lease ownership epoch after the DB dispatch
  guard has been acquired, not just before entering the possibly blocking DB
  transaction. On stale ownership, no Binance request is sent.
- Monotonic InnoDB order fact merges prevent old `NEW` snapshots from
  overwriting `PARTIALLY_FILLED` or terminal `FILLED` facts, reducing
  cumulative filled quantity or silently reassigning orders across strategies.
- Dispatch ACK now requires a positive exchange order ID. Recovery will not
  rewrite an existing conflicting ticket's exchange order ID.
- `GET /quant/infrastructure/dispatch/unresolved` exposes up to 100 active
  persistent dispatch blocks under `quant:dispatch:view`. The market page
  displays a read-only queue. There is intentionally no force-unlock route.
  Binance/Snowflake IDs must remain JSON strings.
- Existing MySQL 8.4/InnoDB concurrency tests exercise lease epoch takeover,
  no-automatic-UNKNOWN dispatch retry, single-claim semantics, immutable fill
  attribution and order status/filled-quantity monotonicity.

## Explicitly NOT yet cleared / requires deployment environment

| Release blocker | Evidence or dependency needed |
| --- | --- |
| Actual Binance testnet/staging fault injection | Operator-provided isolated accounts with enabled permissions; simulate POST accepted then HTTP timeout, duplicate client IDs, fill during cancel, algo stop trigger during cleanup and private stream outage. Reconcile Binance REST facts against database *and* actual position/protection before approval. |
| Remote in-flight order fencing | Binance cannot reject our internal DB epoch. Validate a single accountable dispatch authority, node termination while HTTP in flight, exchange idempotency, new owner recovery and protective-order takeover. A durable DB guard reduces duplication but is not a remote atomic transaction. |
| Canonical production schema and migrations | Sanitized actual `SHOW CREATE TABLE` and current data inventory for all quant tables, baseline migration history, versioned additive upgrades V01–V03, backup/restore drill, rehearsal on a clone, duplicate cleanup and rollback/forward recovery. Never install disposable test DDL on production. |
| Full strategy-level position / PnL allocation | A signed fill-to-position event ledger for OPEN/PARTIAL_CLOSE/CLOSE, fee treatment, multiple fill bursts, commissions, manual orders, legacy positions, isolated vs hedge and reduce-only invariants. Current unique fill ownership rows do **not** authorize shared account-symbol strategies. |
| Operator incident recovery | Authenticated exact-fact remediation with immutable audit trail and independent approval, including `DISPATCHING` left by process crash. The existing read-only unresolved queue is diagnostic only and deliberately does not auto-release. |
| End-to-end production verification | Staging multi-node chaos, market/user WebSocket snapshot/replay tests, recovery/kill-switch drills, credential rotation, observability alert thresholds, rollout and rollback validation and live-protection handover rehearsal. |

## LIVE default: operator-directed, but NOT execution approval

As expressly requested, `QuantProperties.live.enabled` and the dev/prod
`QUANT_LIVE_ENABLED` default are now `true`. The production strategy
supervisor is still independently `QUANT_RUNTIME_ENABLED:false`. This default
does not bypass LIVE database schema checks, per-account authorization, order
idempotency/dispatch guard, or strategy-level gates. On any unapproved
deployment **set `QUANT_LIVE_ENABLED=false` explicitly**.

No claim of real-funds production acceptance is made without the external
evidence above. Repository CI does not access real Binance credentials or a
real deployed database.

## Follow-up fixes: crash-safe recovered dispatch and fill audit

- `ExecutionRecoveryService` now rejects wrong-symbol Binance order facts
  and refuses terminal CANCEL recovery with missing, negative or excessive
  cumulative executed quantity. It similarly filters fallback order matches.
- A new `QuantExecutionRecordMapper.strandedRecoveredDispatches` query scans
  the exact **active** ticket held by a matching `RECOVERED` execution
  record. This closes the process-crash window between committing
  `RECOVERED` and releasing the durable dispatch guard. Every attempted
  unlock re-queries the exact Binance order identity, reconfirms terminal
  CANCEL and requires a persisted local order ledger record.
  No timer-based force release is introduced.
- The read-only `StrategyFillAuditService` and
  `GET /quant/infrastructure/fill-audit/summary` endpoint require
  `quant:allocation:view`; identifiers and all decimal totals are returned
  as strings. The execution ledger screen offers a manual query form.
  Aggregated, deduplicated **lifetime verified** BUY/SELL quantities, prices,
  fees and realized PnL are useful for reconciliation, but **cannot** be
  treated as live open-position quantity or used for liquidation/risk sizing.
- New SQL, crash/restart and strict arithmetic/identity regressions are
  part of the critical GitHub CI job. None of these changes substitutes
  for testnet outage drills, real schema migration or signed fill-to-position
  accounting with cycle identity.

### Additional outbound-account mutation audit

`TradingPreparationService` still calls `gateway.setMarginType` and
`gateway.setLeverage` from a background preparation task using a JVM-local
account-symbol lock. Its preconditions check positions and approval, but it
has **not** yet been serialized with the durable cross-node
`LiveOrderDispatcher` guard. These account-configuration API mutations can
race with another node; they need a separate verified-settings dispatch
protocol because they have no exchange order ID to use as an ACK.
**Do not interpret order/algo dispatch coverage as coverage of account
configuration mutations.**

