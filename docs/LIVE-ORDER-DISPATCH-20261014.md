# LIVE outbound dispatch — durable order tickets (2026-10-14)

## Implemented

`UnifiedTradeExecutionService` now routes LIVE `OPEN`, `CLOSE` and `CANCEL`
operations through `LiveOrderDispatcher` before contacting Binance. The exchange
gateway remains the official pinned Binance Java SDK 12.0.3 adapter, with its
previously documented WebSocket options.

A short MyBatis/Spring `REQUIRES_NEW` transaction atomically claims one
`quant_live_dispatch_guard` slot per `(credential_id,symbol)` and creates one
`quant_order_dispatch_ticket` per `(credential_id,idempotency_key)`.
The transaction **commits before the Binance network call**. No database row
lock is held while waiting for Binance. This design intentionally sacrifices
availability under an unresolved outcome to prevent cross-node double sends.

| Fact | Ticket status | Dispatch guard |
| --- | --- | --- |
| Claimed, exchange call not yet confirmed | `DISPATCHING` | Held |
| Definitely not sent or explicitly rejected | `FAILED_NOT_SENT` | Released after atomic update |
| Accepted by Binance AND local order ledger durable | `ACKED` | Released after atomic update |
| Timeout, network uncertainty or local write failure | `UNKNOWN` (or `DISPATCHING` on crash) | **Held indefinitely** |
| Verified exact exchange order and ledger recovered | `RECOVERED` | Released in same transaction |

A failed insert or duplicate ticket **rolls back the guard claim**.
A stale node cannot take a guard merely because a TTL expired. An operator
must **never manually DELETE/NULL the active guard** merely to unstick orders.

The existing `ExecutionRecoveryService` can release an unresolved LIVE ticket
only after it has synchronized the Binance order snapshot, updated the
execution record to `RECOVERED`, and matched the exact Binance client order ID.
For a cancellation, it only attempts release when the exchange order is
CANCELED, EXPIRED, FILLED or PARTIALLY_FILLED, **not** when it is still NEW.
Orders lacking a recoverable clientOrderId remain quarantined until audited
manual reconciliation is implemented.

## Required database migration

1. Disable LIVE on **all** nodes; stop the strategy supervisor and outbound
   order activity; resolve or quarantine in-flight requests.
2. Verify a backup/restore drill and the currently deployed schema.
3. Install `script/sql/quant/V20261014_01__runtime_lease_epoch.sql`, if absent.
4. Install `script/sql/quant/V20261014_02__live_dispatch_guard.sql` **exactly once**.
5. Run `script/sql/quant/production-preflight.sql`. The LIVE startup guard
   additionally rejects missing dispatch tables/uniqueness.
6. Deploy all nodes together; do not run legacy direct-LIVE dispatch workers
   alongside the new guard because they do not honor it.
7. Validate recovery with a controlled network-timeout simulation before
   considering real money.

**Do not apply CI's disposable MySQL test schema to production.**

## Boundaries and blockers

- This version covers the primary unified entry/exit/cancel execution path.
  Protection/algorithm order APIs and other direct exchange requests require a
  separate audit and dispatch integration before claiming a truly single
  outbound gateway.
- Binance cannot validate an internal fencing epoch. A remote HTTP request
  already in flight may still be accepted after a newer owner takes over. The
  durable single-writer guard reduces this window, but a complete centralized
  dispatch authority and proven takeover procedure are still required.
- No generic automatic timeout release is safe. Operations on the same
  account+symbol will be blocked until precise recovery establishes the fact.
- Strategy-level allocation ledger, full partial-fill accounting, database
  baseline migrations, operator reconciliation queue, Binance staging fault
  injection and production rollback drills are still outstanding.
- A green GitHub CI run establishes unit/Integration SQL behavior, **not**
  production authorization. LIVE permission now defaults to **ON** per operator request, while production strategy runtime defaults **OFF**. Set `QUANT_LIVE_ENABLED=false` until all production gates have passed.
