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

---

# Red Team / Blue Team Audit

## Round 1: Red Team — Attack Vectors

| Attack | Cible | Couvert ? | Risque |
|--------|-------|:---------:|--------|
| **Crash entre event.append() et trade INSERT** | Epic N (N.2) | ❌ | JVM crash dans la fenêtre entre l'append SQLite et l'insert trades → event existe mais trade pas. Pas de reconstruction au startup |
| **SQLite DB corrompu** | Epic N | ❌ | `PRAGMA integrity_check` jamais exécuté. Si le fichier .db est tronqué par un crash disque, trades et events sont perdus en silence |
| **Multi-fill sur un seul ordre** | Epic N (N.7) | ⚠️ Partiel | N.7 couvre le partial fill, mais pas 3+ fills partiels contre le même ordre (ex: LIMIT à 1.1000 rempli à 0.4+0.3+0.3). Besoin d'un `parent_order_id` |
| **Boucle de reconnect infinie** | Epic N+1 (N+1.5) | ❌ | Backoff 1s→60s OK, mais pas de *hard limit* total. Une run peut rester en état RUNNING avec des reconnect failures pendant des heures. Besoin d'un max-attempt total avant FAILED |
| **OANDA 503 transitoire** | Epic N+1 (N+1.1) | ❌ | 2 failures consécutifs → disconnection. Une rafale de 503 sur 5s déclenche 60s de downtime. Besoin d'un circuit breaker (half-open, pas binaire) |
| **Re-run en boucle de la recovery** | Epic N+3 (N+3.4) | ❌ | Si la recovery échoue 3×, le StaleRunWatchdog arrête de retenter (limite 3/h). Mais la run reste en FAILED avec positions orphelines sur OANDA. Besoin d'escalade |
| **Leak mémoire dans run_records** | Epic N+3 (N+3.1) | ❌ | Pas de purge des vieux `run_records`. SQLite ralentit avec 10k+ rows. Besoin d'une TTL + archive |
| **Deux stratégies sur le même symbole** | Epic N+2 | ❌ | Stratégie A et B sur EUR_USD. Le `getPositions()` broker retourne les positions combinées. Chaque stratégie voit l'autre comme faisant partie de son PnL. Besoin de isolation par `clientTag` |
| **JVM crash après trade.write() avant event.append()** | Epic N (N.2) | ❌ | L'inverse du premier: si l'ordre d'opérations est trade puis event, un crash donne trade sans event → perte de l'audit log. Besoin d'ordre atomique garanti |
| **Fill asynchrone d'un LIMIT/STOP pendant une déconnexion** | Epic N+1 | ❌ | Si on est déconnecté 5s et qu'un LIMIT se fill pendant ce temps, on rate le FILL. Au reconnect, le trade est sur OANDA mais pas dans trades. Pas de story pour "rattrapage des fills manqués" |

## Round 2: Blue Team — Défenses Manquantes

| Défense | Pourquoi c'est critique | Story suggérée |
|---------|------------------------|----------------|
| **Audit: event count vs trade count** | Seule façon de prouver que la synchro events→trades fonctionne | N+5.1 |
| **Startup integrity_check** | Sans ça, une DB corrompue passe inaperçue jusqu'à ce que les requêtes échouent | N+5.2 |
| **Test auto: restart survivability** | Sans test, on ne saura pas que la persistence marche avant le premier vrai restart | N+5.3 |
| **Reconciliation report (cron)** | Comparer trades locaux vs positions OANDA = seule preuve que le système est cohérent | N+5.4 |
| **Alerte haut taux de rejet** | >30% d'ordres rejetés sur 1h = OANDA ou config défaillante | N+5.5 |
| **Alerte stale heartbeat via notification** | Une run stale doit envoyer une notification Telegram/Discord, pas juste logguer | N+5.5 |
| **Write-latency monitor sur trades** | Si INSERT prend >100ms, contention DB | N+5.7 |
| **Startup check: trades vs events divergence** | Au démarrage, vérifier que le compte des FILL events = nombre de trades pour la même run | N+5.1 |
| **Self-diagnostic endpoint** | `GET /api/diagnostics/integrity` — un endpoint qui fait tout ça en un appel | N+5.6 |
| **Rétention & archive des vieux trades** | Purge automatique des trades > 90 jours avec export CSV avant | N+5.8 |

---

## Epic N+5 — Verification & Malfunction Detection

**Objectif** : Chaque fix des Epics N à N+4 est vérifiable automatiquement. Un rapport de malfunction est produit quotidiennement. L'intégrité du système est auto-surveillée.

### User Stories (Valeur Métier)

| # | As a [role]... | Value |
|---|----------------|-------|
| US-N+5.1 | Trader | I want an automated report showing that my trades are consistent between the event log and the trades table | Trust that the fix works |
| US-N+5.2 | Trader | I want to know immediately if a restart caused trade loss | Catch regressions fast |
| US-N+5.3 | Trader | I want a daily email/Telegram saying "All systems nominal — X trades tracked, Y positions open" | Operational confidence |

### Coding Stories

| # | Story | Effort | Dépend de | Description |
|---|-------|--------|-----------|-------------|
| **N+5.1** | Start-of-day event/trade reconciliation | M | N.1, N.2 | On startup, for each run in events.db: count FILL events vs trades in trades table. If mismatch → emit `DATA_DIVERGENCE` event + log WARN with runId, eventCount, tradeCount, delta. Exclude runs still in progress (trades can arrive after startup) |
| **N+5.2** | SQLite integrity_check on every connection open | XS | N.1 | Add `PRAGMA integrity_check` after every `SqliteBacktestRunStore` and `SqliteEventStore` connection init. If integrity fails → emit INTEGRITY_FAILURE event, log ERROR, and set a `dbCorrupted` flag exposed via `/api/health` |
| **N+5.3** | Integration test: kill and restart | M | N.6, N+3.3 | New test class `PaperTradingSurvivabilityTest`: (1) start a harness strategy in PAPER mode via RunManager, (2) verify trades appear in trades table, (3) simulate JVM kill (close event store, clear ConcurrentHashMap), (4) reconstruct state from SQLite, (5) assert trades table data survived. Runs as `mvn test -pl trading-runtime -Dtest=PaperTradingSurvivabilityTest` |
| **N+5.4** | Cron: daily reconciliation report | M | N+1.6, N.4 | New cron job (or scheduled task in ControlPlaneServer) that runs every 6h: queries trades table, queries broker positions, computes diff. Emits structured report to `/control/summary` under `reconciliation` key: `{"lastRun": "ISO8601", "matched": 12, "unmatchedLocal": 0, "unmatchedBroker": 0, "staleBrokerPositions": []}` |
| **N+5.5** | Alerting: high reject rate & stale runs | S | N+4.2 | When `ORDER_REJECT` rate exceeds 30% in 1h moving window → emit `HIGH_REJECT_RATE` operator event. When a run is stale for >5min → emit Telegram/Discord notification (not just log). Configurable threshold |
| **N+5.6** | Self-diagnostic endpoint | S | N+5.1, N+5.2 | `GET /api/diagnostics/integrity` — runs on demand: (1) PRAGMA integrity_check on both DBs, (2) event/trade count match for last 10 completed runs, (3) broker connectivity test. Returns JSON with pass/fail per check, plus duration |
| **N+5.7** | Trade write-latency monitoring | XS | N.1 | Instrument every trade INSERT with a timer. Log WARN if >100ms. Expose p50/p95/p99 latency in `/control/summary` under `dbLatency.trades.insert` |
| **N+5.8** | Data retention & archival policy | S | N.1 | Add `created_at` index to trades table. Cron job to purge trades >90 days (configurable). Before purge, export to CSV in `data/archive/trades/`. Log `ARCHIVED` event with count of purged rows |

**Total Epic N+5**: ~3 jours

---

## Epic N+6 — Backtest vs Live/Paper Drift Comparison (Epic 37)

**Objectif** : Automatically compare how a strategy performs in backtest vs in live/paper trading, detect statistically significant drift, and expose a side-by-side report via REST and the TUI.

**État des lieux** : Un `DriftEngine` existe déjà (`trading-runtime/.../DriftEngine.java`), utilisé par `DriftSignalService` et exposé via `/control/summary` sous la clé `signals.drift`. Il compare drawdown, win rate et trade volume entre une baseline backtest (déploiement promote) et les runs broker. Mais il est limité :
- Ne compare que 3 métriques (drawdown, win rate, trade volume)
- Pas de Sharpe, Profit Factor, Sortino, avg trade PnL, equity curve shape
- Pas d'endpoint REST dédié
- Pas de cron de rapport autonome
- Pas de test statistique (seulement des seuils fixes)
- Impossible de comparer une run arbitraire sans passer par un déploiement

### User Stories (Valeur Métier)

| # | As a [role]... | Value |
|---|----------------|-------|
| US-N+6.1 | Trader | I want to see a side-by-side comparison of my strategy's backtest metrics vs its paper trading metrics | Know if the strategy is behaving as expected |
| US-N+6.2 | Trader | I want an alert when paper performance diverges significantly from backtest | Catch problems early |
| US-N+6.3 | Trader | I want to see at a glance: "this strategy is running X% worse than backtest" | Decide whether to stop/pause |

### Coding Stories

| # | Story | Effort | Dépend de | Description |
|---|-------|--------|-----------|-------------|
| **N+6.1** | Extend DriftEngine with full metric suite | M | N.2, N.4 | Add comparison for: Sharpe ratio, Profit Factor, Sortino, Calmar, avg trade PnL. For each metric, compute: (1) backtest value, (2) paper/live value, (3) delta (absolute + %), (4) threshold status (GREEN/YELLOW/RED). Store thresholds in `DriftThresholds` |
| **N+6.2** | Match strategy runs to backtest baseline via parameter hash | S | N+6.1 | When a paper run starts (or at report time), find the matching backtest run by `strategyId + symbol + parameterHash`. Use the most recent backtest with the same hash. If no match → emit `NO_BASELINE` signal |
| **N+6.3** | Add `GET /api/drift/comparison` endpoint | M | N+6.1 | Returns per-strategy comparison: backtest metrics, live metrics, delta, status per dimension, overall recommendation. Query params: `?strategyId=X` or omit for all. Paginated. Response shape mirrors `DriftEvaluation` but with richer metric data |
| **N+6.4** | Add `comparison` section to `/control/summary` | S | N+6.3 | Include a `comparison` key in the summary payload showing a compact version of the drift comparison for each running strategy (backtest vs live Sharpe, drawdown, win rate, trade count) |
| **N+6.5** | Statistical significance: add minimum sample check | S | N+6.1 | Before flagging drift, verify the paper run has ≥15 trades OR ≥7 days of observation. Below those thresholds, report `INSUFFICIENT_DATA` instead of a false alarm. Configurable via `DriftThresholds` |
| **N+6.6** | Cron: automated BT-vs-paper comparison report | S | N+6.3 | Scheduled task (every 12h) that runs `GET /api/drift/comparison` and emits a structured report. If any strategy has RED status → emit `DRIFT_ALERT` event and notify via Telegram/Discord. If all GREEN → emit `DRIFT_OK` event (no notification, just log) |
| **N+6.7** | TUI `/compare` command | S | N+6.4 | Terminal command showing per-strategy comparison table: columns = metric (Sharpe, WinRate, DD, PF), backtest value, live value, delta %, status (✅/⚠️/🔴). Color-coded |
| **N+6.8** | Contingency: parameter mismatch detection | XS | N+6.2 | If paper run's `configHash` differs from backtest baseline's `parameterHash`, include `CONFIG_MISMATCH` in the report with the delta fields. The user can then decide if the strategies are even comparable |
| **N+6.9** | Contingency: timeframe mismatch guard | XS | N+6.2 | If backtest used H1 bars but paper run receives M5 ticks, mark the comparison as `TIMEFRAME_MISMATCH` — they're not directly comparable. Skip drift computation |

**Total Epic N+6**: ~4 jours

---

## Updated Priority / Build Order

| Phase | Epic | Effort | Why first |
|-------|------|--------|-----------|
| **4.1** | Epic N — Trade-Level Audit & Persistence | ~4j | Foundation — everything else depends on structured trade data |
| **4.2** | Epic N+1 — Connection Resilience | ~5j | Second — stops data loss from connectivity failures |
| **4.3** | Epic N+2 — Monitoring & Observability | ~2.5j | Third — surface the data that Epics N and N+1 produce |
| **4.4** | Epic N+3 — Stateful Run Recovery | ~4j | Fourth — requires Epic N (trades table) and N+1 (reconnect) |
| **4.5** | Epic N+4 — Logging & Diagnostics | ~1.5j | Can start in parallel with 4.2 (independent) |
| **4.6** | Epic N+5 — Verification & Malfunction Detection | ~3j | Proves everything above actually works |
| **4.7** | **Epic N+6 — BT vs Paper Drift Comparison** | ~4j | Last — requires trades table (N) and event log (N+4) |

**Total revised**: ~24 jours

---

## Red Team Summary: Top 3 Gaps (Highest Risk)

| Gap | Epic | Why High Risk | Mitigation Added |
|-----|------|---------------|------------------|
| **Crash between event.append() and trade INSERT** | N.2 | Silent data loss — event exists but trade doesn't. Hard to detect without N+5.1 reconciliation | N+5.1 (startup reconciliation), N+5.3 (survivability test) |
| **OANDA 503 → 60s unnecessary disconnection** | N+1.1 | Circuit breaker is binary when it should be half-open. 2×30s = 60s of missed trades for a 5s blip | Add circuit breaker with half-open state + minimum-ok-count before full recovery |
| **Deux stratégies sur le même symbole sans isolation** | N+2 | Unnoticed PnL attribution errors. Les positions broker sont combinées, les deux stratégies voient l'autre comme partie de leur performance | Add `clientTag` filtering in `getPositions()`: chaque stratégie ne voit que ses positions taggées. Les positions non-taggées → `UNKNOWN` pool |

## Key Decisions (Decision Log)

| Decision | Rationale |
|----------|-----------|
| **Trade data goes into its own SQLite table** (not JSON columns) | Queryable trade history without full event-scan. Trades table is the source of truth for positions; events table is the append-only log |
| **Broker REST keepalive is 30s with 2-failure threshold** | OANDA API SLA is <5s. 30s avoids false positives from transient latency spikes. 2 failures = genuine outage |
| **Reconnect before restart in watchdog** | State preservation is cheaper than state recreation. Reconnect keeps positions and bar aggregator alive |
| **Duplicate-run guard is strict by default, overridable** | Prevents the most common operator error (starting the same strategy twice) while allowing forced restart for bug recovery |
