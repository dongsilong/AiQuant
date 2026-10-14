# AiQuant LIVE branch audit — 2026-10-14

Scope: `hardening/live-production-20261014`. This is a code/schema-contract review,
**not** a production account certification or an exhaustive all-files static analysis.

## P0 — still blocking unattended LIVE trading

1. **No verified canonical initial schema**. `script/sql/quant/README.md`
   explicitly states that deployed quant table DDL is not known. V01 is an
   ALTER on a pre-existing lease table. V02–V10 are additive/review-only.
   Consequently the combined V01–V10 script is NOT a from-scratch full
   RuoYi/AiQuant installation SQL and is NOT safe to blindly run in production.
2. **Accepted Binance orders and cross-node HTTP outcomes**. Durable dispatch
   and pre-send intent improve safety, but a Binance request accepted during
   a crash/takeover cannot be revoked by an internal lease epoch. Real
   multi-node and isolated-Binance failure acceptance is not evidenced.
3. **Automatic strategy inventory projector is not integrated with the
   production reconciliation scheduler.** `TradingReconciliationService`
   allocates fills to `StrategyFillAllocationService`, not to a proved
   complete-history `StrategyFillReplayService` / position-cycle projector.
   `StrategyFillReplayService` accepts a caller-supplied boolean
   `completeHistoryVerified`; there is no durable exchange watermark,
   atomic checkpoint, or automated missing-page recovery.
4. **Funding and multi-asset fee accounting not closed**. Fee denomination
   is now carried by `TradeSnapshot` and mixed-currency aggregations
   refuse unsafe totals. But Binance funding-income transactions are not
   attributed to proven strategy position ownership at the funding time.
   Cumulative portfolio cost/PnL is therefore not authoritative.
5. **Real Binance account vs strategy inventory reconciliation missing**.
   The equality guard requires externally supplied quantity snapshots and
   completeness flags, but the live gateway account-position-to-projected-
   positions verification and proof of common watermark are not wired.
6. **Recovery approvals/audit aren't externally immutable**. DB triggers
   prevent routine UPDATE/DELETE; a privileged DBA can remove them. No
   verified WORM export/independent retention or production least-privilege
   grants. Recovery execution still needs isolated end-to-end validation.
7. **Production database migration not accepted**. GitHub Actions MySQL 8.4
   validates clean-schema V02/V03/V05–V10 and a logical backup/restore
   synthetic drill. V01 depends on an existing lease baseline; V04 and
   production's actual table definitions need a restored production clone
   plus migration drift check. No production backup or restore evidence is
   present in repository.

## P1 / contract risks seen in the review

- `production-preflight.sql` does not enumerate V05–V10 objects,
  immutable audit triggers or the newer fee-asset columns; thus a successful
  preflight alone cannot establish the latest schema is installed.
- `StrategyPositionProjectionMapper.ensure` uses `INSERT IGNORE`.
  Some database constraint errors can be demoted to warnings; subsequent
  locked read checks owner identity, but strict error visibility is preferable.
- `StrategyFillReplayService` can commit earlier events before identifying a
  later missing order attribution; it stops after the first gap but does not
  provide a durable batch quarantine state or atomic history checkpoint.
- `StrictBinanceTradeCollector` deliberately rejects a saturated
  1000-trade window, rather than implement full id-cursor paging. This is
  safe but leaves heavy-volume backfill unavailable.
- `V06` requires MySQL command-line `DELIMITER` handling, not a generic
  JDBC statement execution loop.
- The strategy position inventory aggregates fees under one asset per
  inventory row. This prevents false cross-currency summation but cannot
  express positions paying commissions in multiple legitimate assets.
- Existing bootstrap compatibility with all prior deployed RuoYi database
  objects has not been checked using the authoritative deployed DDL.

## SQL deliverables

- `script/sql/quant/ALL_V20261014_01_TO_10_REVIEW_ONLY.sql` —
  combined verbatim current-branch V01–V10 migration content (reference).
- `script/sql/quant/V20261014_01__runtime_lease_epoch.sql` through
  `V20261014_10__pre_dispatch_strategy_intent.sql` — ordered individual
  incremental SQL files.
- `script/sql/quant/production-preflight.sql` — read-only historical
  contract scan; incomplete for newer V05–V10 additions.
- `docs/LIVE-MYSQL-MIGRATION-RESTORE-RUNBOOK-20261014.md` — validated
  disposable-CI drill vs required, *not-yet-performed* production-clone test.

**Release decision: NO-GO for unattended LIVE runtime.** Obtain sanitized
actual deployed DDL, verify migration drift on a restored production
clone, finish Binance-specific projection and funding attribution, and
complete recovery/chaos/WORM acceptance before turning on runtime.
