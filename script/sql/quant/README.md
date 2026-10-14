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
