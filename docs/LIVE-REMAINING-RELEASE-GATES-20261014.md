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

### Seven-blocker closure attempt — evidence-gated, not claimed as complete

The repository adds `script/security/check_live_release_evidence.py` and
offline regression tests. The script rejects absent evidence, a self-reviewing
operator, invalid evidence paths and mismatched SHA256 checksums. Run it on a
separate deployment evidence bundle using
`python3 script/security/check_live_release_evidence.py manifest.json --evidence-root /path/to/evidence`.
It validates manifest integrity, NOT the content or authenticity of captured
exchange facts. An independent reviewer must verify the evidence.

`V20261014_05__recovery_case_audit.sql` is a **design-only additive migration**
for two-person recovery case metadata and append-only audit event records. It
is NOT applied, not a completed recovery workflow, and does NOT expose any
manual unlock endpoint. Operator recovery still requires a carefully reviewed
service/controller state machine with fresh Binance identity, position and
protection facts, synchronized DB state, and separate authorization.

The required evidence categories are `ci`, `schema_backup_restore`,
`binance_fault_injection`, `multi_node_fencing`,
`position_pnl_reconciliation`, `operator_recovery_approval`, and
`deployment_rollback_monitoring`. The manifest must reference the exact
release commit and two different reviewers. Production approval remains blocked
until each artifact exists and its findings are independently accepted.

**Remaining implementation gap:** a strategy-level signed inventory accounting
ledger and exercised operator recovery workflow are not yet implemented. The
database baseline and all real-network/database drills cannot be completed from
this repository without isolated authorized systems. Keep the independent
runtime supervisor disabled until genuine approval.

### Recovery approval CAS groundwork (not dispatch remediation)

The reviewed `V20261014_05` migration draft now enforces that an APPROVED
or EXECUTED case must have a reviewer distinct from its requester.
`LiveRecoveryApprovalMapper` adds guarded request/approve/reject SQL:
requests must match the current UNKNOWN/DISPATCHING live dispatch slot, and
a request cannot be approved by its own requester or if the immutable
evidence hash changed. Approval also rechecks that the same dispatch ticket
remains active. Static regression checks run in CI.

This mapper is **not exposed as an authenticated controller**, does not
write immutable audit events yet, and deliberately has **no execute/unlock**
method. It requires an operator-authenticated service, transactionally
linked audit inserts, durable independent approval, fresh Binance order
and position verification, concurrency/restart tests and a reviewed migration
before use. Do not treat an APPROVED database row as permission to resume
LIVE trading.

### Transactional approval audit added (still no unlock)

`LiveRecoveryCaseService` now wraps request, approve and reject CAS operations
together with an INSERT-only `LiveRecoveryAuditMapper` event in one Spring
transaction. An audit insert failure causes rollback of the case transition;
invalid IDs and SHA256 digests fail before DB access. Rejection also checks
the immutable evidence digest. A dedicated regression covers successful
commit, audit failure rollback, stale CAS and invalid digests.

These methods currently accept actor IDs from their caller and therefore
**must not be exposed to remote requests** until an authenticated permission
boundary obtains actor identity from the server security context rather than
request parameters. Audit immutability requires MySQL privileges and
independent WORM archival. This is still NOT operator remediation: approvals
never query Binance, execute recovery or release a dispatch guard. V05 needs
a verified deployment schema and rehearsal before its tables can be used.

### Approval race / stale exchange-ticket hardening

The uninstalled V05 draft now has a MySQL generated nullable active-case key
and UNIQUE index, preventing two concurrent PENDING/APPROVED approval cases for
the same credential, symbol and dispatch key even if both requests passed
NOT EXISTS concurrently. APPROVED transitions also require the exact active
ticket to remain UNKNOWN/DISPATCHING and preserve operation type, client order
ID, and nullable exchange order ID. The SQL contract test checks these
invariants. This is not yet a real-MySQL migration or concurrency validation.

### Manual recovery execution path — implemented, staging gate still open

`LiveManualRecoveryController` provides four separately permissioned POST
endpoints at `/quant/infrastructure/recovery/{request,approve,reject,execute}`.
The actor comes only from the Sa-Token session, never from a request field.
The independent requester and reviewer must differ; execution requires a
**third** distinct authenticated actor.

`LiveManualRecoveryExecutionService` obtains the persisted APPROVED case,
checks exact credential/symbol/client ID/order ID, reads an exact Binance order
via the configured ExchangeGateway, validates order identity, known status
and cumulative quantities, and requires a terminal fact for CANCEL.
A missing/uncertain Binance order NEVER authorizes an unlock. The order
snapshot must persist before the short MySQL transaction; then the execution
record transition, approval-case EXECUTED CAS, durable dispatch ticket recovery,
guard release and EXECUTE audit insert must all commit together. SQL status
conditions reject a parallel/stale executor. Uncertain outcomes remain blocked.

**Not yet production accepted:** V05 is a draft needing canonical schema
migration rehearsal; explicit permission grants must be reviewed and
separated across identities. MySQL grant restrictions plus WORM audit export
must be verified. The exchange fact and protection/position lifecycle still
require isolated Binance fault-injection and multi-node tests. A fresh exact
order snapshot alone does not prove full account/protective-order equivalence,
and the flow intentionally excludes CONFIG_SETTINGS and protection tickets.
Existing standard reconciliation remains available for those distinct types.

No LIVE trade is automatically resumed merely because a case is APPROVED.
