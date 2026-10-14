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

- This version covers unified LIVE entry/exit/cancel **and the ProtectionOrderService
  algorithm-order placement, explicit retry, cancellation, and lifecycle cleanup
  paths** through the same durable account-symbol dispatch slot. An accepted
  protection order is acknowledged only after its exact Binance algo ID is persisted
  locally. Uncertain placement or cancel retains the slot. Subsequent exact algo
  query and local ledger update can safely recover the matching active ticket;
  wrong algo ID/client algo ID may not unlock it.
- Other direct gateway callers and manual/external order requests still require
  a full outbound path audit before claiming a universal single-writer gateway.
  Unknown outcomes may deliberately block further trading pending operator review.
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

## Additional OMS race and operator hardening

- Normal LIVE cancel now resolves the authentic Binance client order ID
  before reserving a cancel execution record when the caller supplies only
  an exchange order ID. It does not invent a synthetic client ID that could
  later make exact recovery impossible.
- A successful CANCEL API response is **not** terminal proof. The dispatcher
  releases the account-symbol slot only after exact Binance `queryOrder`
  confirms one of `CANCELED`, `EXPIRED`, `FILLED`, `REJECTED`, checks
  both identifiers and cumulative executed quantity, persists the local
  order snapshot, and updates the execution record. `PARTIALLY_FILLED`
  remains quarantined; the remaining unfilled quantity may still be live.
- The recovery scheduler follows the same rule. It may reconcile a partial
  fill's ledger facts without marking CANCEL `RECOVERED` or unlocking
  the corresponding unresolved ticket.
- Once a strategy captures a lease epoch, it revalidates it before AND
  after the durable dispatch-guard acquisition. Losing the lease during
  a blocked claim therefore causes a `FAILED_NOT_SENT` ticket, without
  contacting Binance.
- `QuantOrderMapper.mergeExchangeFactIfMonotonic` performs conditional SQL
  updates: older `NEW` facts cannot replace confirmed partial fills or
  terminal statuses, cumulative fills cannot decrease, and strategy or
  exchange-order identity cannot silently change. H2 and disposable
  MySQL/InnoDB regression tests cover competing snapshots.
- Privileged operators may inspect the 100 oldest unresolved account-symbol
  dispatch tickets at `GET /quant/infrastructure/dispatch/unresolved`,
  requiring `quant:dispatch:view`. Binance/Snowflake IDs are exposed as
  strings. The market administration page displays this **read-only** queue;
  there is no administrative force-unlock endpoint.

**Caveat:** A status-safe SQL merge is not a complete fills/fees PnL ledger.
Some manual/external orders, snapshot-history gaps, order-cancel retry paths,
and close-protection takeover conditions still need Binance staging fault
injection. No LIVE production execution is authorized solely by CI.

## Account configuration writes: one persistent CONFIG_SETTINGS ticket

`TradingPreparationService` previously used only a JVM-local lock while
calling Binance `changeMarginType` and `changeInitialLeverage`. Other
application nodes could interleave exchange orders or incompatible settings
between those two calls.

The LIVE setup path now claims the **same** persistent
`quant_live_dispatch_guard(credential_id,symbol)` slot with one
`CONFIG_SETTINGS` ticket **before** either exchange write. The complete
margin-mode + leverage sequence is protected by this single ticket.
Before starting the remote writes it rechecks the local preparation revision,
fresh account balance and position absence *after* database claim. On success
of **both** SDK calls, `configurationApplied()` atomically changes the
ticket to `CONFIG_APPLIED` and releases the guard. The config ACK path is
separate from ordinary order ACK because Binance account settings have no
exchange order ID; no synthetic exchange ID is ever stored.

If either API call times out, an exception occurs mid-sequence, or the node
dies before the acknowledgement transaction, the ticket remains
`UNKNOWN` or `DISPATCHING` and continues blocking account-symbol order
dispatch across all nodes. There is deliberately **no automatic retry,
TTL takeover or timeout-based force unlock**. A read-only operator queue
shows these blocked config tickets and their requested settings identifier.
A future independently verified Binance configuration snapshot and audited
operator recovery procedure are still necessary before safe automated
unblocking; HTTP 200 from an SDK setter is not equivalent to independently
verifying the final account configuration.

This prevents configuration writes from overlapping order dispatch only for
code paths using the new persistent guard; old application versions must
never run alongside the new writers. It does NOT protect writes made
manually/external to AiQuant, and it is not a remote exchange-enforced
fencing protocol.

## Cross-node desired-settings contract (V04)

A persistent dispatch ticket prevents overlap *during* a margin/leverage write,
but does not by itself prevent one node configuring 10× immediately after
another node configured 20×. The new
`quant_live_account_settings_intent` table stores one immutable desired
`(credential_id,symbol,margin_mode,leverage)` contract.

`beginConfiguration` atomically claims the same durable dispatch guard,
inserts the desired contract if absent, and rejects any conflicting mode or
leverage before touching Binance. A conflicting configuration rolls back the
claim; it cannot disturb an existing strategy's approved settings or hold a
spurious UNKNOWN guard. The ordinary `begin` API explicitly refuses
`CONFIG_SETTINGS`, making it impossible for a caller to bypass this contract.

**Migration order:** deploy only after validating and applying V01 lease epoch,
V02 durable dispatch, V03 verified fill attribution, and
`script/sql/quant/V20261014_04__account_settings_intent.sql` while
all older nodes and LIVE strategy execution are stopped. The LIVE schema
startup check now requires this table and its primary key.

**Changing leverage/margin later:** the immutable desired contract must be
revised through a separately audited maintenance procedure with no live
positions, working orders, UNKNOWN/CONFIG tickets, or active strategies.
The current public API intentionally offers no automatic update/force
release. This contract prevents conflicting AiQuant nodes but does not
prove that external users have not modified exchange-side settings; private
WebSocket invalidation, regular preflight checks and testnet verification
remain mandatory.
