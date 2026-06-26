# Epics & Stories — Paper Trading Reliability & Monitoring

> **Phase 3 — Solutioning (BMad Method)**
> **Project**: Trading Bridge
> **Author**: Martin Fournier (PO) + BMad Party Mode (Winston Architect, Amelia Dev, John PM)
> **Date**: 2026-06-26
> **Track**: BMad Method

---

## Context

The user **does not trust Trading Bridge in PAPER_OANDA mode**. Concrete issues identified during codebase audit:

| # | Bug / Gap | File | Evidence |
|---|-----------|------|----------|
| B1 | OANDA streaming runs report `totalTrades=0` at end | `RunManager.java:473-477` | `BacktestResult.builder().totalTrades(0)` — hardcoded, ignores `OandaStreamingExecutor.filledCount` |
| B2 | All run metadata is in-memory; lost on restart | `RunManager.java:108-112` | `ConcurrentHashMap<String, RunRecord> runs` — no persistence |
| B3 | No independent trade table in SQLite schema | `SqliteEventStore.java:142-156` | Events stored as JSON blobs only; no structured `trades` table |
| B4 | OandaBroker.connect() does single API call, no keepalive | `OandaBroker.java:41-48` | `client.fetchAccountSummary()` + `connected=true`. `reconnect()` is never called anywhere |
| B5 | StaleRunWatchdog creates brand-new runs on restart | `StaleRunWatchdog.java:79-96` | `stop()` + `register()` → loses accumulated state |
| B6 | No duplicate-run guard | `RunManager.java:352-371` | `startRun()` has no dedup check |
| B7 | Connection drop detection exists in streaming client but broker reconnect is missing | `OandaStreamingClient.java:129-143` `OandaBroker.java:52-54` | Stream watchdog reconnects stream, but OandaBroker REST stays dead |

### Failure Modes & Contingencies

| Failure | Scenario | Contingency |
|---------|----------|-------------|
| **Network drop** | Control plane loses internet mid-trade | Position freeze → stream reconnect → position reconciliation |
| **OANDA API rate limit** | Too many submitOrder calls in short window | Rate limiter with 429 detection + exponential backoff |
| **Partial fills** | Order is partially filled | Track remaining unfilled quantity; emit PARTIAL_FILL event |
| **Market closed** | Strategy sends order outside trading hours | Reject with MARKET_CLOSED reason; resume on next valid bar |
| **Weekend gap** | Weekend price gap triggers stale signals | Market-hours liveness filter (ignore stale during known closed periods) |
| **Stale price** | Price hasn't updated but stream is alive | Configurable max-price-age per symbol |
| **Run-startup race** | Watchdog creates new run while executor still processing last bar | `RunRecord.lock` — atomic status transition |
| **Duplicate run** | Same strategy started twice | `strategyId + symbol + mode` uniqueness constraint |
| **Credential expiry** | OANDA token expires during prolonged run | Renewal hook + graceful pause |
| **Data source mismatch** | Ingested bars from wrong timeframe | DataSource timestamp validation at run start |

---

## Epic N — Trade-Level Audit & Persistence

**Objectif** : Every paper trade is durably recorded in a structured SQLite table, survives restart, and is queryable via REST.

### User Stories (Valeur Métier)

| # | As a [role]... | Value |
|---|----------------|-------|
| US-N.1 | Trader | I want to see a complete list of all my trades — past and current — even after restarting the control plane | Trust that nothing disappears |
| US-N.2 | Trader | I want to see per-trade PnL (USD) including commissions and slippage | Track strategy performance |
| US-N.3 | Trader | I want to filter trades by strategy, symbol, date range, and outcome | Analyze without replaying events |
| US-N.4 | Trader | I want to know my open positions at any moment without guessing | Risk awareness |

### Coding Stories

| # | Story | Effort | Dépend de | Description |
|---|-------|--------|-----------|-------------|
| **N.1** | Create `trades` SQLite table | S | — | Schema: trade_id, run_id, strategy_id, symbol, side, entry_price, exit_price, quantity, entry_time, exit_time, pnl_usd, commission, slippage, status(OPEN/CLOSED) |
| **N.2** | Migrate event replay → trade extraction | M | N.1 | Replace `ControlSummaryService.calculatePnLMetrics()` event-replay-on-read with write-time trade recording in `BrokerRunExecutor` and `OandaStreamingExecutor` |
| **N.3** | Fix `totalTrades=0` for OANDA streaming | XS | N.2 | `RunManager.java:476` — pass `filledCount` from `OandaStreamingExecutor` into `BacktestResult` |
| **N.4** | Add `GET /api/trades` REST endpoint | S | N.1 | Filterable by runId, strategyId, symbol, status(OPEN/CLOSED), date range; paginated |
| **N.5** | Add `GET /api/trades/summary` endpoint | XS | N.4 | Aggregated per-strategy: total trades, win rate, avg PnL, net PnL |
| **N.6** | Rebuild positions from trades table after restart | M | N.1 | On control-plane startup, scan trades table for OPEN trades and restore position state |
| **N.7** | Contingency: detect and flag partial fills | S | N.2 | When fill quantity < order quantity, emit PARTIAL_FILL event; `trades` table gets `filled_qty` vs `requested_qty` |

**Total Epic N**: ~4 jours

---

## Epic N+1 — Connection Resilience & Keepalive

**Objectif** : The broker connection survives network hiccups, OANDA API blips, and long idle periods without losing state or requiring manual restart.

### User Stories (Valeur Métier)

| # | As a [role]... | Value |
|---|----------------|-------|
| US-N+1.1 | Trader | I want the platform to survive a brief internet outage without losing my trades or positions | Don't lose money to connectivity |
| US-N+1.2 | Trader | I want a clear status indicator if the broker is disconnected | Know when I'm flying blind |
| US-N+1.3 | Trader | I want automatic reconnection without duplicate positions or lost fills | Set and forget |

### Coding Stories

| # | Story | Effort | Dépend de | Description |
|---|-------|--------|-----------|-------------|
| **N+1.1** | Add REST keepalive to OandaBroker | M | — | Scheduled heartbeat pinging `GET /v3/accounts/{id}` every 30s; if 2 consecutive failures → `connected=false` + emit `BROKER_DISCONNECT` event |
| **N+1.2** | Implement `OandaBroker.reconnect()` | S | N+1.1 | Reset HTTP client, re-authenticate, re-stream. Called by watchdog on connection-loss detection |
| **N+1.3** | Connection-state event stream | S | N+1.1 | New `RunEventType.CONNECTION` — emitted on connect/disconnect/reconnect. Consumers: heartbeat events for TUI, summary endpoint, watchdog |
| **N+1.4** | StaleRunWatchdog: reconnect before restart | M | N+1.2 | Before creating a new run, try `broker.reconnect()` + wait up to 30s. Only restart if reconnect fails |
| **N+1.5** | Exponential backoff on broker reconnect | XS | N+1.2 | 1s → 2s → 4s → ... → 60s cap; reset on success. Track retry count in `/control/summary` |
| **N+1.6** | Contingency: position reconciliation after reconnect | M | N.2, N+1.2 | After reconnecting, fetch OANDA open positions and compare with local trades table. Emit `RECONCILIATION` event with diff (expected vs actual) |
| **N+1.7** | Contingency: OANDA API rate-limit guard | S | — | Track `submitOrder` calls per minute. If >50/min, queue orders with delay. Emit `RATE_LIMIT` event when queued |
| **N+1.8** | Contingency: market hours / weekend detection | S | — | Check if current time is within known forex trading hours before submitting orders. Emit `MARKET_CLOSED` event with next-open estimate |
| **N+1.9** | Contingency: stale price detection | XS | — | If `OanaStreamingExecutor.lastMidPrice` hasn't changed in 5 minutes but stream is active, emit `STALE_PRICE` event and flag in `/control/summary` |

**Total Epic N+1**: ~5 jours

---

## Epic N+2 — Monitoring & Observability

**Objectif** : Every critical metric is exposed via REST, logged, and visible in the TUI and control summary. The user can answer "is the platform working right now?" in one glance.

### User Stories (Valeur Métier)

| # | As a [role]... | Value |
|---|----------------|-------|
| US-N+2.1 | Trader | I want to see "last trade executed" with timestamp and PnL for every running strategy | Know the system is alive |
| US-N+2.2 | Trader | I want to know when the broker last responded successfully vs failed | Diagnose connectivity issues |
| US-N+2.3 | Trader | I want to see a heartbeat for each strategy: seconds since last event, bars processed | Trust execution |
| US-N+2.4 | Trader | I want immediate notification if the same strategy is started twice | Avoid accidental duplication |

### Coding Stories

| # | Story | Effort | Dépend de | Description |
|---|-------|--------|-----------|-------------|
| **N+2.1** | Add `totalTrades` to OandaStreamingExecutor BacktestResult | XS | N.3 | Actually fix the bug: pass `filledCount` from executor to `BacktestResult` in `RunManager.java` |
| **N+2.2** | Enrich heartbeat events with trade metadata | S | N.2 | `HeartbeatEvents.emitBarHeartbeat` now includes `runningTradeCount`, `lastFillTime`, `openPnl` when executor is active |
| **N+2.3** | Contingency: duplicate-run prevention | S | — | `startRun()` checks `runManager.list(RUNNING)` for same `strategyId + symbol + mode`. Emit `DUPLICATE_RUN` event and reject if already running. Overridable with `force=true` |
| **N+2.4** | Contingency: run-startup race condition guard | XS | N+2.3 | Use `AtomicBoolean` or `ReentrantLock` per strategyId in RunManager to serialize start attempts |
| **N+2.5** | Add broker-health endpoint: `GET /api/broker/health` | XS | N+1.1 | Returns connected=true/false, lastResponse, lastFailure, uptimeSeconds |
| **N+2.6** | Add `lastTradeAt` to `/control/summary` per-run | XS | N.2 | Time of most recent FILL/REJECT event for each running strategy |
| **N+2.7** | Add stale-detection log4j markers | XS | — | Structured MDC logging for stale/heartbeat/timeout events — searchable in log files |
| **N+2.8** | Health-check CLI command in TUI | S | N+2.5 | `/health` — displays broker connection, DB status, running strategy count, last heartbeat timestamp |

**Total Epic N+2**: ~2.5 jours

---

## Epic N+3 — Stateful Run Recovery (Restart-Safe)

**Objectif** : After a control-plane restart, all active runs are automatically restored to their pre-restart state with correct position tracking.

### User Stories (Valeur Métier)

| # | As a [role]... | Value |
|---|----------------|-------|
| US-N+3.1 | Trader | I want my running strategies to survive a control plane restart | Fault tolerance |
| US-N+3.2 | Trader | I don't want to manually re-start strategies after a crash | Operational simplicity |

### Coding Stories

| # | Story | Effort | Dépend de | Description |
|---|-------|--------|-----------|-------------|
| **N+3.1** | Create `run_records` SQLite table | M | — | Schema: run_id, strategy_id, symbol, mode, config_snapshot (JSON), status, started_at, last_event_at, created_at. Persists every run-record transition |
| **N+3.2** | Migrate RunManager to DB-backed RunRecord storage | L | N+3.1 | Replace (or wrap) in-memory `ConcurrentHashMap` with `SqliteRunRecordStore`. Fallback to in-memory for fast path with periodic sync |
| **N+3.3** | Auto-restore runs on control-plane startup | M | N+3.2, N.2 | On startup, scan `run_records` for status=RUNNING. For each, reconstruct OandaStreamingExecutor from stored config + trades table open positions. Resume streaming |
| **N+3.4** | StaleRunWatchdog: use reconnect-first, then clean restart | M | N+1.4, N+3.1 | Instead of stop+register, attempt reconnect; if reconnection succeeds, resume existing run (don't lose state). Track restart count in run_records table |
| **N+3.5** | Contingency: crash-safe transaction boundary | S | N+3.1 | Use SQLite transaction around event-append + trade-record-insert. If either fails, both roll back — no phantom trades |

**Total Epic N+3**: ~4 jours

---

## Epic N+4 — Logging & Diagnostics Infrastructure

**Objectif** : Every significant system event (connection, disconnection, order rejection, heartbeat timeout) is logged with structured context, making post-mortem analysis fast.

### User Stories (Valeur Métier)

| # | As a [role]... | Value |
|---|----------------|-------|
| US-N+4.1 | Developer | I want structured log entries for every trade event with runId, strategyId, and symbol | Debug without grepping raw JSON |
| US-N+4.2 | Developer | I want a log-based audit trail showing every state transition in the run lifecycle | Trace exactly what happened |

### Coding Stories

| # | Story | Effort | Dépend de | Description |
|---|-------|--------|-----------|-------------|
| **N+4.1** | Add structured logging to OandaBroker methods | XS | — | Every connect/disconnect/reconnect/submitOrder gets log with `runId`, `symbol`, `mode`, duration |
| **N+4.2** | Add run-lifecycle audit events to EventStore | S | — | New event types: `BROKER_CONNECT`, `BROKER_DISCONNECT`, `RECONNECT_ATTEMPT`, `RECONNECT_FAILURE`, `RATE_LIMIT_TRIGGERED`, `STALE_PRICE_DETECTED` |
| **N+4.3** | Add `GET /api/events/{runId}/audit` endpoint | XS | N+4.2 | Returns lifecycle events filtered by type, sorted by sequence, paginated |
| **N+4.4** | Contingency: log rotation & size management | S | — | Add log4j2 RollingFileAppender config with max 10 files × 100MB. Keep 7 days of history |

**Total Epic N+4**: ~1.5 jours

---

## Priority / Build Order

| Phase | Epic | Effort | Why first |
|-------|------|--------|-----------|
| **4.1** | Epic N — Trade-Level Audit & Persistence | ~4j | Foundation — everything else depends on structured trade data |
| **4.2** | Epic N+1 — Connection Resilience | ~5j | Second — stops data loss from connectivity failures |
| **4.3** | Epic N+2 — Monitoring & Observability | ~2.5j | Third — surface the data that Epics N and N+1 produce |
| **4.4** | Epic N+3 — Stateful Run Recovery | ~4j | Fourth — requires Epic N (trades table) and N+1 (reconnect) |
| **4.5** | Epic N+4 — Logging & Diagnostics | ~1.5j | Can start in parallel with 4.2 (independent) |

**Total estimated**: ~17 jours

---

## Key Decisions (Decision Log)

| Decision | Rationale |
|----------|-----------|
| **Trade data goes into its own SQLite table** (not JSON columns) | Queryable trade history without full event-scan. Trades table is the source of truth for positions; events table is the append-only log |
| **Broker REST keepalive is 30s with 2-failure threshold** | OANDA API SLA is <5s. 30s avoids false positives from transient latency spikes. 2 failures = genuine outage |
| **Reconnect before restart in watchdog** | State preservation is cheaper than state recreation. Reconnect keeps positions and bar aggregator alive |
| **Duplicate-run guard is strict by default, overridable** | Prevents the most common operator error (starting the same strategy twice) while allowing forced restart for bug recovery |
