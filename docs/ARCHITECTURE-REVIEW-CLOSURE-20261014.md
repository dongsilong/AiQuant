# Architecture-review recommendations: status against LIVE hardening branch

Source: user-provided AiQuant source review originally scoped to
`ai-quant-dev` and `ai-quant-ui-dev`. This checklist compares selected
recommendations to the different, evolving hardening branch. Existence of
code is not production acceptance.

| Recommendation | Current assessment | Required closure |
| --- | --- | --- |
| Keep StrategyPlugin and ExchangeGateway abstraction | Present | API contract / plugin compatibility regression |
| Refactor StrategyRuntimeService into coordinators | Incomplete | Extract session, event, signal, state and protection responsibilities with tests |
| Version strategy configs and persistent state | Not verified | Versioned schema, replay/idempotency migration, restart regression |
| Canonical market-event protocol and stream dedupe | Partial | Event identity / per-feed recovery tests and bounded replay |
| Two-level execution-command vs exchange-order states | Partial | Explicit independent state machines and illegal-transition tests |
| Lease epoch and account-symbol durable dispatch | Implemented in branch | Multi-node and in-flight HTTP crash acceptance |
| Signed-short position count | Corrected in current branch | CI and full integration results for RiskSignedPositionCountTest |
| Account capacity atomic reservation | Present | Concurrent opening, timeout, crash, partial-fill MySQL tests |
| Recovery scan fixed 24h window | Corrected in branch | Fairness/claim behavior under backlog and multi-node chaos |
| Entry signal idempotency vs account conflict separate keys | Incomplete | Versioned key migration, strategy-instance/interval-aware replay tests |
| Strategy-level signed positions and realized PnL | Incomplete, P0 | Fill-to-position event ledger, fees, cycles, manual trades, hedge checks |
| LIVE operator recovery workflow | Partial, P0 | Authenticated endpoints, Binance exact-fact execution, WORM evidence, rehearsal |
| Real database migrations V01-V05 | Draft only, P0 | Verify deployed schema and restore-tested additive migrations |
| Binance fault injection and multi-node fencing | Unverified, P0 | Isolated exchange test credentials and observed trade/order facts |
| LIVE release alerts, rollback, handover | Unverified, P0 | Actual staging deployment drills and approval evidence |
| Front-end trade terminal decomposition | Not re-audited | Review actual ai-quant-ui-dev tree before change |
| Financial decimal handling in UI | Not re-audited | Exact strings for money/order inputs, BigDecimal backend contract |
| Backtest engine | Deferred if LIVE-only scope | Do not block LIVE-only release; keep explicit scope decision |
| Portfolio Engine and research-plane architecture | Not complete | Design guarded limits before enabling strategy sharing |

## Release policy

Keep `QUANT_RUNTIME_ENABLED=false` until all true P0 execution gates pass.
LIVE feature default does not grant production trade permission.
Do not mark a P0 complete on the basis of a class, mapper, migration draft,
mocked test or GitHub CI alone. Record both repository commit and isolated
environment observations. No force-unlock of unknown Binance outcomes.
