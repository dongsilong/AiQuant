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
| Canonical production schema and migrations | Sanitized actual `SHOW CREATE TABLE` and current data inventory for all quant tables, baseline migration history, versioned additive upgrades V01–V04, backup/restore drill, rehearsal on a clone, duplicate cleanup and rollback/forward recovery. Never install disposable test DDL on production. |
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

`TradingPreparationService` now claims an account-symbol persistent dispatch
slot across both Binance `setMarginType` and `setLeverage` mutations, and
acknowledges a `CONFIG_APPLIED` ticket only after both SDK requests succeeded.
The V04 `quant_live_account_settings_intent` table enforces one immutable
desired margin/leverage policy per account-symbol across nodes; conflicting
settings are refused before any exchange write. An uncertain config request
remains `UNKNOWN`/`DISPATCHING` without automatic release. This guards known
AiQuant writers. The service now verifies Binance exchange-side settings after
both setters and again at periodic LIVE readiness renewal; mismatch or missing
readback fails closed, without silently reapplying settings. This readback is
not a remote atomic transaction, does not guarantee the setting remains
unchanged between renewal and order submission, and does not permit automatic
restart recovery. Audited operator recovery, cross-node fault injection and
staging acceptance remain production release blockers.


### Next round: require the current dispatch slot during verified recovery

The MyBatis `recoverVerified` transition now additionally checks that the
exact ticket still holds `quant_live_dispatch_guard.active_dispatch_key`
for the same credential and symbol. This is checked in the UPDATE itself,
not only in a preceding application read. A regression test covers both a
mismatching guard (zero affected rows, UNKNOWN preserved) and a matching
guard (RECOVERED). The surrounding dispatch service keeps the ticket update
and guard release in one transaction. This is defense in depth, **not**
permission to auto-recover UNKNOWN requests or force-unlock tickets.
Operator-approved exact-fact remediation and live chaos evidence remain open.

### 2026-10-14 execution identity CAS hardening and verification boundary

The execution-record mapper now refuses `markAccepted` or
`markRecoveredAfterFact` if a persisted positive Binance exchange order ID
conflicts with the proposed ID. Null or nonpositive proposed IDs are rejected.
The regression test asserts that competing recovery workers cannot replace
the recorded order identity. The critical workflow also runs
`LiveDispatchRecoveryGuardSqlTest`.

**Still unverified, not automatically approved for funds:**

1. CI must complete successfully at the exact release commit (critical, entire
   quant regression, and MySQL/InnoDB integration). Failed checks block release.
2. Operator must export a sanitized canonical MySQL schema and migration
   inventory; rehearsal must be performed on a restored clone with rollback
   evidence, NOT against production first.
3. An isolated Binance account must verify accepted-then-timeout, cancel versus
   fill, duplicate client IDs, stream disconnect, process death during outbound
   HTTP, hedge/reduce-only, protective orders, and cross-node takeover. Record
   exact Binance order/trade/position/protection facts and compare local ledgers.
4. For an unresolved `CONFIG_SETTINGS` ticket, **do not** recover it using the
   ordinary order recovery method. Verify actual margin/leverage, open positions,
   exchange orders and no in-flight writers, then perform separate auditable
   operator maintenance; never clear a dispatch guard by editing SQL manually.
5. Operator remediation requires an independent approver, authenticated actor,
   immutable evidence of the exact exchange object and database transaction
   result. Neither the read-only queue nor a GET success authorizes an unlock.
6. Test secret rotation, kill switch, observability alerts, durable recovery,
   deployment rollback, and a fresh protection-order handover before release.

This work branch is **NOT production-certified** until all the above evidence
is attached to a release review and independently accepted.
