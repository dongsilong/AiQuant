# LIVE safety hardening — next-round changes (2026-10-14)

This file records **implemented** hardening and release blockers; it does not certify readiness for real capital.

## Implemented in this branch

1. **Fact collection outside transactions** — `TradingReconciliationService` now retrieves Binance account, position, open and historical orders, trades, protective orders and income before opening one bounded MyBatis/Spring database transaction for write-through. If Binance data gathering fails, no partial account facts are persisted.
2. **Do not cancel real protections on local-data gaps** — the full reconciler no longer automatically cancels live Binance protective orders merely because a local `quant_position` row cannot be matched. Such unmapped live protections produce a `DEGRADED` reconciliation record with a count for operator review. The UI labels this result as requiring manual protection review. **Do not mistake this for full orphan-order cleanup.**
3. **Restricted LIVE position model** — `OpenCapacityService` rejects a second strategy entry on the same Binance account and symbol when there is an open position or unreconciled open-capacity reservation, **including the opposite hedge direction**. Existing matching idempotency requests are still recognized. This is an interim safety boundary, not a strategy-level fill allocation ledger; manual trades and imported positions still need operator reconciliation.
4. **Final outbound lease check** — `UnifiedTradeExecutionService` checks the exact `instance_id`, `credential_id`, normalized symbol and owner token against a non-expired database lease immediately before a new LIVE runtime order reaches `gateway.placeOrder`. Failures or missing validator fail closed. Existing MySQL integration tests assert that the previous owner is rejected after takeover.

## Still NOT resolved — LIVE deployment blockers

- **Full fencing:** Binance does not understand our internal fencing tokens. A lease DB read followed by HTTP request has an unavoidable race. A monotonic fencing epoch, centralized per-account+symbol outbound order ownership/serialization, takeover replay and tested failure semantics are still needed for multi-node execution.
- **Shared strategy positions:** no authoritative per-strategy fill allocation, realized PnL splitting or invariant-preserving reduction ledger. The single-account-symbol restriction reduces risk but does not replace this design.
- **Protected orphan review workflow:** unmapped stop orders need an operator queue / alert and verified position-cycle/order provenance before cancellation; never use an unverified local absence as proof of exchange closure.
- **Operational validation:** failover tests against actual Binance endpoints, delayed acknowledgements, partially filled/canceled order races, protection replays, prolonged disconnected streams, unique-index migrations, secrets rotation and deployment rollback drills remain required.

## Current gate

Both `QUANT_LIVE_ENABLED` and `QUANT_RUNTIME_ENABLED` remain off by default.
A passing Maven, H2, Mockito or MySQL CI run establishes a regression baseline, **not** an approval to enable capital.
