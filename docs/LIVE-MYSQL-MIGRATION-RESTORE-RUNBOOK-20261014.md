# MySQL 8.4 LIVE schema migration and restore acceptance

## Scope and evidence

There are **two independent gates**, and one must not stand in for the other.

1. **Disposable-CI recovery rehearsal** (`mysql-integration` job):
   `ci-mysql-backup-restore-drill.sh` executes against the MySQL 8.4
   ephemeral service only. It verifies logical backup SHA-256, restores into
   a separate schema, simulates total loss of *that* restored schema,
   restores again, and checks exact immutable pre-dispatch strategy intent,
   `UNKNOWN` dispatch state, uniqueness and FK constraints.
2. **Staging clone of the actual deployed database** (NOT performed by CI):
   required to accept the production migration. The repository still does
   not contain a verified canonical deployed schema baseline. It would be
   unsafe to run these migration drafts blindly on a live account.

## Operator runbook for actual staging-clone rehearsal

All operations below require DBA authorization. Do **not** put real database
secrets in GitHub Actions. Keep `QUANT_RUNTIME_ENABLED=false` across every
node. Block new exchange HTTP sends, and preserve unresolved `UNKNOWN`
dispatch tickets. Confirm no migration SQL is applied to the primary LIVE
database during this rehearsal.

1. Obtain production schema with DBA-approved **read-only** credentials:
   `SHOW CREATE TABLE` for each deployed `quant_*` table and sanitized
   `information_schema` indexes/constraints. Export an inventory with the
   MySQL server version, table row counts, and UTC capture time.
   Review differences before choosing migration steps. Do not assume an
   empty database, or that V01–V10 have not already been installed.
2. Use the approved backup tool to generate an encrypted, consistent
   InnoDB backup (logical `--single-transaction` only if all relevant tables
   are InnoDB; otherwise coordinate writes or use a physical snapshot).
   Record **SHA-256 of the backup**, source GTID/binlog position, retention
   and recovery-point objective. A checksum does NOT prove the backup is
   recoverable.
3. Restore the backup into a separately provisioned MySQL 8.4 environment
   with **no production network routes to Binance and no LIVE credentials**.
   Assert original table count, row counts, representative immutable event
   IDs and primary/foreign keys. Verify restored read-only OMS/reconciliation
   queries. Stop if discrepancies exist.
4. Apply individually reviewed, forward-only V01–V10 migrations in the
   required dependency order. V01 requires an existing lease table;
   V02 defines the dispatch ticket referenced by V10; V05 precedes V06.
   Do not apply V06 with a generic SQL migration parser because it contains
   `DELIMITER` client directives. Record every statement, start/end time,
   operator, SQL SHA-256 and pre-/post-schema signature in a separate
   change log. MySQL DDL is not automatically rolled back on failure.
5. Force safe, disposable test failures: terminate the clone session during
   a transaction; restart the MySQL clone during a prepared but unsent
   dispatch; confirm `UNKNOWN` remains blocked; run a duplicate
   `quant_strategy_pre_dispatch_intent` insert and assert unique rejection;
   attempt orphan insert and assert FK rejection. Verify the audit triggers
   reject UPDATE/DELETE while a read-only auditor can SELECT.
6. Restore the **pre-migration backup into another new staging database**.
   Verify the original dataset signature and data invariants, not just the
   existence of tables. Measure recovery time and the observed data-loss
   interval; attach the operator-reviewed results. Application-level
   downgrade may require forward-fix, not reversing nontransactional DDL.
7. Run `production-preflight.sql` read-only on the upgraded clone and the
   approved application compatibility tests. Cross-check real Binance
   position, order, fee and protection facts in an isolated account. Only a
   signed acceptance record can close this gate. Keep runtime disabled if
   any step fails or any unresolved order remains.

## Minimum acceptance evidence

Record: environment identifier (no secrets), sanitized actual DDL and drift
diff, applied-script digests, backup identifier/sha256, backup restoration
log, restored row counts and invariant checksum, recovery duration and
transactional failure observations, permission checks, independent DBA /
trading-risk sign-off and the exact backend Git SHA. Store those results in a
separately protected evidence system with retention policy.

**CI PASS means the synthetic disposable exercise succeeded; it does not
attest production backup access, production schema compatibility, or a
production-clone failover drill.** Never flip the LIVE runtime switch solely
because CI was green.
