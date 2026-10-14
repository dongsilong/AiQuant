# LIVE strategy fill attribution — first audited increment

The exchange exposes account-aggregated positions, not a trusted strategy share.
Consequently this implementation **does not** proportionally allocate fills from
account position size. It only attributes an individual Binance trade to a
strategy when the exact local order row already records a verified
`strategy_id` and `strategy_instance_id` and its exchange order ID, account
and symbol match the fill.

## Invariants

- The append-only `quant_strategy_fill_allocation` table uses
  `UNIQUE (credential_id,symbol,exchange_trade_id)`.
- Each immutable row stores order ID, strategy instance, exact trade quantity,
  price, fee, realized PnL, side and exchange execution time.
- A repeated fill is accepted **only** when allocation source and monetary
  values match. An alternate strategy, quantity, price, fee or PnL is a
  safety error and cannot replace the original allocation.
- LIVE full reconciliation writes the fill allocation inside the same
  short database transaction as the local trade fact.
- Missing or manual/unmapped fills remain in the account trade ledger, but
  are not fabricated into a strategy share. Any such LIVE fills produce
  `DEGRADED` with `unattributedFills` in the reconciliation record.
- The operator UI labels `DEGRADED` as requiring review of protective
  orders **or fill ownership**.

## Deploy (not automatic)

After securing a tested database backup and disabling LIVE on every node,
review/apply **in order**:

1. `script/sql/quant/V20261014_01__runtime_lease_epoch.sql`
2. `script/sql/quant/V20261014_02__live_dispatch_guard.sql`
3. `script/sql/quant/V20261014_03__strategy_fill_allocation.sql`

Run `script/sql/quant/production-preflight.sql` and verify the startup guard
accepts all required InnoDB tables and unique keys. Never run destructive CI
DDL against live data, never mix legacy direct-LIVE workers with new dispatch
writers, and never automatically backfill unverified historical trades.

## Remaining P0 work

- A complete per-strategy **position** ledger using signed fills, direction,
  partial reductions and realized/unrealized PnL settlement.
- Accurate reconciliation of pre-existing positions and manual entries.
- Per-strategy protection target/share coordination for complex hedge mode.
- OMS partial-fill/cancel/timeout failover drills and verified stale-owner
  containment after HTTP was already sent.
- Historical fill attribution/backfill from authoritative, immutable exchange
  facts, and an operator workflow for ambiguous allocations.

Neither the uniqueness tests nor this partial attribution ledger are permission
to support shared-symbol strategies or enable real capital by default.
