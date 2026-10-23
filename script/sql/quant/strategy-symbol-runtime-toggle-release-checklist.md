# Per-strategy-symbol runtime toggle — release and acceptance checklist

Scope: branch `feature/per-symbol-runtime-toggle-20261023` in `dongsilong/AiQuant` and the matching branch in `dongsilong/AiQuant-ui`.

This checklist is a manual release gate. Passing CI does not prove behavior against a real Binance TESTNET/LIVE account. Do not deploy, apply SQL, or start/stop a real strategy solely because this checklist exists.

## 1. Preconditions

- [ ] Confirm the intended backend and UI commit SHAs and that both CI workflows are green.
- [ ] Confirm the target environment, DB instance, deployment package, and maintenance window with the operator.
- [ ] Ensure LIVE strategy supervisors are stopped on every application node before schema changes; resolve or record in-flight order dispatches.
- [ ] Take an encrypted backup and verify that restoration has been tested.
- [ ] Verify encryption-key compatibility with existing encrypted credentials. Do not rotate a data-encryption key without a separately planned re-encryption procedure.
- [ ] Set all required `AIQUANT_*` secrets through the deployment secret manager. Never place real secrets in Git, shell history, tickets, or this checklist. Previously committed credentials must be rotated.
- [ ] Use a read-only database identity for preflight inspection.

## 2. Inspect schema before migration

Run `script/sql/quant/production-preflight.sql` against the intended database using a read-only account. Review every returned row; do not assume a green CI database matches production.

Inspect the actual table:

```sql
SHOW CREATE TABLE quant_strategy_symbol;
SHOW COLUMNS FROM quant_strategy_symbol LIKE 'runtime_enabled';
```

Decision:
- If the column is absent, continue only after confirming the target DB, backup, maintenance window, and reviewed migration.
- If the column already exists, do not rerun the migration. Verify type, nullability, default, and current values.
- If the table or schema differs from expectation, stop and prepare a reviewed migration for the actual schema.

## 3. Apply the manual additive migration

Only after the preconditions and schema inspection are complete, execute exactly once:

`script/sql/quant/V20261023_14__strategy_symbol_runtime_enabled.sql`

The application does not auto-apply this migration. Then verify:

```sql
SHOW COLUMNS FROM quant_strategy_symbol LIKE 'runtime_enabled';

SELECT runtime_enabled, COUNT(*) AS row_count
FROM quant_strategy_symbol
GROUP BY runtime_enabled;
```

Expected: `runtime_enabled` is `TINYINT(1)`, `NOT NULL`, with default `1`. Existing bindings should be enabled by the column default. Do not use a destructive rollback casually: dropping the column while new application code is running breaks the runtime toggle. Any rollback requires stopping all upgraded nodes and an explicit, reviewed schema/application rollback plan.

## 4. TESTNET acceptance (no real funds)

Use a dedicated TESTNET credential and a low-risk test symbol/account. Record timestamps, strategy binding ID, symbol, API response, application logs, order IDs, and observed position state. Never include API secrets in evidence.

1. Start a strategy-symbol binding with `runtime_enabled=1`; verify ordinary entry evaluation is active.
2. Pause that one binding in the terminal. Verify the UI shows it paused and the backend response succeeds.
3. Verify the database row persists `runtime_enabled=0`; refresh the terminal and confirm the paused state remains.
4. With no position open, verify the paused binding does not dispatch a new entry. Verify another enabled symbol/binding still evaluates normally.
5. With an existing TESTNET position, pause the binding and verify take-profit/stop-loss evaluation, protection maintenance, reconciliation, and exit handling remain active. The pause action must not itself close the position.
6. Resume the binding; verify `runtime_enabled=1` persists and entry evaluation can resume.
7. Restart the application and repeat the UI/database checks to verify persistence across restart.
8. Exercise permission denial with a user lacking `quant:strategy:edit`; the endpoint must reject the change.
9. Verify an ID belonging to a different strategy cannot be toggled through the current strategy's endpoint.
10. Check concurrent pause/resume and entry evaluation in logs. Confirm distributed-lock behavior, no duplicate order dispatch, and no cross-symbol state leakage.

Do not treat a UI state change alone as proof. Cross-check DB state, runtime logs, and TESTNET order/position events.

## 5. LIVE release gate

LIVE validation requires a separately approved change window and explicit operator authorization. Before enabling LIVE:
- [ ] Complete the TESTNET acceptance above with evidence.
- [ ] Confirm the deployed backend and UI commits match the reviewed release.
- [ ] Confirm all required production secrets exist in the secret manager and previously committed credentials have been rotated.
- [ ] Confirm the production schema migration and read-only preflight results have been reviewed.
- [ ] Confirm all nodes run the same compatible release and lease/schema requirements are satisfied.
- [ ] Start with no intentional live orders/positions for the test binding, or document the exact existing-position safety plan.
- [ ] Have an operator monitor the first pause/resume cycle and verify the Binance order/position stream.
- [ ] Abort on unexpected entry dispatch, disabled exit/protection handling, stale DB/runtime state, lease conflicts, or unknown in-flight dispatch state.

Do not run LIVE order tests without explicit authorization. Do not enable or disable the global LIVE switch as a substitute for the per-symbol toggle.

## 6. Evidence and sign-off

Record:
- Backend SHA and CI run URL.
- UI SHA and CI run URL.
- Sanitized schema/preflight results.
- Migration execution time and operator (no credentials).
- TESTNET pause/resume, restart persistence, existing-position exit/protection, permission, and cross-binding test evidence.
- Any unresolved failures and the operator's go/no-go decision.

A release is not complete until the environment-specific checks are signed off. CI success alone is necessary but not sufficient.
