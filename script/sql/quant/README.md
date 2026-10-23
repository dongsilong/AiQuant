# AiQuant LIVE database schema preflight

The repository does not yet contain a verified, canonical, versioned **production** DDL
baseline for the quant-specific tables. Do not treat the disposable schemas in H2/MySQL
unit tests as production migrations.

## Safe inspection

Run [production-preflight.sql](production-preflight.sql) using a **read-only** database user
against the intended MySQL 8.x database. Inspect every result row.

- All required tables must exist and use InnoDB.
- All required unique-index contracts must return `OK`.
- If a table/index is missing, **do not enable LIVE** and do not blindly create it.
  Export the actual existing schema, check duplicates and current data ownership,
  then prepare a reviewed and independently tested additive migration.
- Take an encrypted verified backup before migrating. Confirm a restoration procedure,
  maintenance window, affected strategies and rollback strategy.
- Never use the destructive `MySqlAtomicConcurrencyTest` on any database outside the
  dedicated disposable `aiquant_ci_test`; the test explicitly rejects other names.

## V14 per-strategy-symbol runtime toggle

[V20261023_14__strategy_symbol_runtime_enabled.sql](V20261023_14__strategy_symbol_runtime_enabled.sql) adds `quant_strategy_symbol.runtime_enabled`, independent of the existing binding `status`. Existing rows default to enabled. The toggle only blocks new entry decisions; it must not close an existing position or disable exit/protection/reconciliation/recovery.

This is a manual additive migration, not an automatic application migration. Before applying it, stop LIVE strategy supervisors on all nodes, resolve or record in-flight outbound requests, verify the actual table with `SHOW CREATE TABLE`, confirm the column is absent, back up the database, and prepare rollback. Apply once, then verify the column default and existing rows before upgrading all application nodes together. Do not run this migration blindly against an unverified production schema.

Follow the [runtime-toggle release and acceptance checklist](strategy-symbol-runtime-toggle-release-checklist.md) for TESTNET verification, permissions, restart persistence, existing-position protection, and LIVE go/no-go evidence.

## Outstanding production migration work

1. Obtain a sanitized `SHOW CREATE TABLE` for each quant table from the authoritative
   deployed schema (not copied from unrelated unit tests).
2. Check column types, nullability, defaults, indexes, foreign keys and transaction engine.
3. Create numbered, forward-only migration scripts with schema-history tracking and
   duplicate preflight checks. Review changes to existing positions and open orders.
4. Test migration, read-reconciliation, live-disabled startup and rollback against a
   restored copy of production data.
5. Repeat the read-only preflight as a release gate.

**The presence of this preflight script does not mean the canonical DDL is finished.**

## Lease epoch additive migration (must precede new LIVE runtime)

The first reviewed additive change is
[`V20261014_01__runtime_lease_epoch.sql`](V20261014_01__runtime_lease_epoch.sql).
This is **NOT an automatic Flyway migration**; the existing application has no
verified quant schema-history baseline. Run it only after checking the current
`SHOW CREATE TABLE` output and confirming the column is missing.

1. Switch LIVE and the strategy supervisor off on **all** nodes; wait for all
   in-flight outbound Binance requests to resolve or be recorded as UNKNOWN.
2. Back up the InnoDB database; inspect unique `(credential_id,symbol)` lease key.
3. Apply the additive migration exactly once, then run `production-preflight.sql`.
4. Upgrade **every** application node before permitting another strategy session.
   Old code deletes released lease rows and can break epoch monotonicity.
5. Test MySQL cross-node takeover and verify epoch 1→2→3 across releases.
6. Only after the full release checklist and Binance fault drills may LIVE be considered.

The runtime checks an identical epoch before and after order preparation. This
**does not** make an already-in-flight remote HTTP request atomic with the database.

## V04 immutable cross-node margin/leverage intent

After reviewed upgrades V01 (lease epoch), V02 (dispatch guard), V03 (verified
fill allocation), the next additive change is
[`V20261014_04__account_settings_intent.sql`](V20261014_04__account_settings_intent.sql).
Install it once during a **LIVE-off / all-old-nodes-stopped** migration window,
after confirming it is absent and validating the actual production schema.

The application startup schema guard and `production-preflight.sql` both
require this InnoDB table's unique `(credential_id,symbol)` key.

The first confirmed desired account-symbol configuration becomes an immutable
contract shared across instances. A different strategy's leverage or margin
mode will be blocked even after the other node has completed its setup. To
change that policy intentionally, use a separately audited maintenance
procedure confirming zero positions/open orders/UNKNOWN dispatches and no
active strategy. The normal API has no automatic update/force-unlock path.

Never assume this immutable *desired* configuration proves current remote
Binance settings, including external/manual changes or API timeouts. The
staging readback/recovery workflow remains an explicit release requirement.
