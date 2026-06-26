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

## Story Standards

### Effort Scale

| Tag | Effort | Équivalent |
|-----|--------|-----------|
| **XS** | ~2h | Fix mineur, 1 fichier, pas de nouveau test |
| **S** | ~4h | Changement localisé, 1-2 fichiers, tests existants |
| **M** | ~1j | Feature modulaire, 3-5 fichiers, nouveaux tests |
| **L** | ~2j | Changement cross-module, 5+ fichiers, test suite |

### Structure Obligatoire par Story

Chaque story doit spécifier :
1. **Fichiers cibles** — Classes Java ou fichiers à créer/modifier
2. **Critères d'acceptation (DoD)** — Conditions explicites pour considérer la story "finie"
3. **Tests** — Comment valider que ça marche (unitaire, intégration, manuel)

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

### Détails d'implémentation — Epic N

| Story | Fichiers cibles | Critères d'acceptation | Tests |
|-------|----------------|------------------------|-------|
| **N.1** | `SqliteEventStore.java` (nouvelle table) ou nouveau `TradeStore.java` dans `trading-backtest/persistence/` | — Table `trades` créée dans le même SQLite DB que events.db<br/>— `PRAGMA table_info(trades)` retourne 13 colonnes<br/>— Migrations forward/backward fonctionnelles<br/>— Index sur `(run_id, exit_time)` créé | `@Test` insert + select + delete sur la table. `@Test` migration rollback |
| **N.2** | `OandaStreamingExecutor.java` (après chaque FILL pair → INSERT trade)<br/>`BrokerRunExecutor.java` (idem)<br/>`ControlSummaryService.java` (supprimer calculatePnLMetrics si remplacé)<br/>Nouveau `TradeWriter.java` (utilitaire write-time) | — Algorithme FIFO : les FILL BUY sont stockés dans une queue per-symbol. Chaque FILL SELL matche le BUY le plus ancien (FIFO). Quantité excédentaire = nouveau BUY partiel.<br/>— Quand un BUY+SELL pair est complété → INSERT dans trades table<br/>— `filledQty` tracké pour les partial fills<br/>— Tous les trades passés sont reconstruits depuis events au startup (rattrapage) | `@Test` BUY→SELL complet → 1 trade en DB<br/>`@Test` BUY→BUY→SELL (2 BUY, 1 SELL partiel) → 1 trade partiel<br/>`@Test` crash recovery : inserer events, simuler restart, vérifier trades reconstruits |
| **N.3** | `RunManager.java:473-477`<br/>`OandaStreamingExecutor.java` (exposer getFilledCount/getTradeSnapshot) | — `totalTrades` dans BacktestResult n'est plus 0 pour les runs OANDA<br/>— La valeur correspond à `filledCount` du OandaStreamingExecutor | `@Test` vérifie que BacktestResult.totalTrades == filledCount après une run |
| **N.4** | `ControlPlaneServer.java` (nouvelle route)<br/>`TradesController.java` (nouveau) | — `GET /api/trades?strategyId=X&limit=20` retourne JSON<br/>— Filtres : strategyId, symbol, status(OPEN/CLOSED), date from/to<br/>— Paginé (offset, limit)<br/>— Réponse < 200ms pour 10k trades | `curl` test sur chaque filtre<br/>Test charge avec 10k trades |
| **N.5** | `TradesController.java` (nouvelle route) | — `GET /api/trades/summary` retourne total, win rate, avg PnL, net PnL par strategy<br/>— Temps de réponse < 100ms | `@Test` compare summary calculé vs requête SQL brute |
| **N.6** | `ControlPlaneMain.java` (hook startup)<br/>`RunManager.java` (nouvelle méthode restorePositions) | — Au startup du control plane, les trades OPEN dans la table sont chargés<br/>— `getPositions()` retourne les positions restaurées<br/>— Si un trade OPEN n'a plus de position correspondante chez OANDA → marqué `STALE` | `@Test` insert 3 trades OPEN, restart mock, vérifier positions = 3 |
| **N.7** | `Trade.java` ou `Order.java` (filledQty field)<br/>`OandaStreamingExecutor.java` (check fill vs order qty) | — Si `filledQty < orderQty` → `PARTIAL_FILL` event émis<br/>— `trades` table a `filled_qty` et `requested_qty` distincts | `@Test` order 100, fill 40 → PARTIAL_FILL émis, filled_qty=40 |

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

### Détails d'implémentation — Epic N+1

| Story | Fichiers cibles | Critères d'acceptation | Tests |
|-------|----------------|------------------------|-------|
| **N+1.1** | `OandaBroker.java` (new ScheduledExecutorService, heartbeat loop) | — Keepalive ping toutes les 30s ±5s<br/>— 2 échecs consécutifs → `connected=false` + BROKER_DISCONNECT event<br/>— Keepalive ne bloque pas submitOrder | `@Test` ping réussi → connected reste true<br/>`@Test` 2 pings échoués → connected=false |
| **N+1.2** | `OandaBroker.java` (reconnect()) | — Reset HttpClient<br/>— Re-fetchAccountSummary<br/>— Resubscribe streaming prices<br/>— Appelable depuis watchdog et manuellement | `@Test` reconnect après disconnect → isConnected = true |
| **N+1.3** | `RunEventType.java` (new CONNECTION type)<br/>`RunEvent.java` (nouveau constructeur ou helper)<br/>`OandaBroker.java` (emit event on connect/disconnect) | — Nouveau `RunEventType.CONNECTION`<br/>— Events contiennent : eventType(CONNECT/DISCONNECT/RECONNECT), brokerAccountId, timestamp<br/>— Visibles dans `/control/summary` | `@Test` connect → CONNECTION event dans EventStore |
| **N+1.4** | `StaleRunWatchdog.java` (reconnect avant restart)<br/>`OandaStreamingExecutor.java` (méthode reconnect) | — Watchdog appelle broker.reconnect() avant stop()+register()<br/>— Timeout reconnect = 30s max<br/>— Si reconnect OK → pas de nouveau runId<br/>— Si reconnect FAIL → fallback à l'ancien comportement | `@Test` stale run + reconnect OK → pas de nouveau runId<br/>`@Test` stale run + reconnect échoue → nouveau runId |
| **N+1.5** | `OandaBroker.java` (backoff dans reconnect)<br/>`ControlSummaryService.java` (retryCount) | — Backoff : 1s → 2s → 4s → 8s → 16s → 32s → 60s cap<br/>— Reset à 1s après un reconnect réussi<br/>— `retryCount` exposé dans `/control/summary` | `@Test` 5 échecs → backoff = 16s<br/>`@Test` 1 succès → backoff reset à 1s |
| **N+1.6** | `OandaStreamingExecutor.java` (post-reconnect reconciliation)<br/>ControlSummaryService | — Après reconnect, fetch OANDA positions via REST<br/>— Compare avec trades table (OPEN trades)<br/>— Émet `RECONCILIATION` event avec {matched, unmatchedLocal, unmatchedBroker}<br/>— Si unmatchedBroker > 0 → alerte | `@Test` réconciliation : 2 trades identiques → 0 diff<br/>`@Test` 1 trade local sans position broker → unmatchedLocal |
| **N+1.7** | `OandaBroker.java` (rate limiter)<br/>`RunEventType.java` (RATE_LIMIT type) | — Compteur de submitOrder/minute<br/>— Seuil : >50/min → queue avec 1s delay<br/>— Émet `RATE_LIMIT` event avec delay + queue size | `@Test` 51 ordres en 1min → 1e mis en queue |
| **N+1.8** | `MarketHoursService.java` (nouveau)<br/>`OandaStreamingExecutor.java` (check avant submit) | — Modèle : FOREX 24/5, fermé vendredi 17:00 NY → dimanche 17:00 NY<br/>— DST supporté : UTC−5 (hiver) / UTC−4 (été) pour NY cutoffs<br/>— Paires exotiques (USD/MXN, USD/ZAR) = heures réduites<br/>— Émet `MARKET_CLOSED` avec nextOpen estimé | `@Test` vendredi 18:00 NY → MARKET_CLOSED<br/>`@Test` lundi 10:00 NY → pas de fermeture |
| **N+1.9** | `OandaStreamingExecutor.java` (stale price check) | — Si `lastMidPrice` identique pendant 5min et stream actif → STALE_PRICE event<br/>— Flag dans `/control/summary`<br/>— Ne bloque pas les trades (info seulement) | `@Test` prix inchangé 6min → STALE_PRICE émis |

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
| **N+2.9** | Fix RunRecord status model for paper trading | S | — | `RunRecord.Status` : remplacer `COMPLETED`/`FAILED`/`ARCHIVED` par `RETIRED`. Garder `RUNNING`/`PAUSED`/`CREATED`. `RUNNING` = stratégie active. `PAUSED` = suspendue temporairement. `RETIRED` = arrêtée manuellement, ne redémarrera pas. Impact : `markCompleted()` → `markRetired()`, `markFailed()` → `markRetired()` |

**Total Epic N+2**: ~3 jours

### Détails d'implémentation — Epic N+2

| Story | Fichiers cibles | Critères d'acceptation | Tests |
|-------|----------------|------------------------|-------|
| **N+2.1** | `RunManager.java` (fix ligne 476)<br/>`OandaStreamingExecutor.java` (exposer filledCount) | — Même critères que N.3<br/>— Story dédiée car priorité haute | `@Test` (idem N.3) |
| **N+2.2** | `HeartbeatEvents.java` (payload enrichi)<br/>`OandaStreamingExecutor.java` (fournir les métriques) | — heartbeat contient : runningTradeCount, lastFillTime, openPnl<br/>— backward compat : payload existant conservé | `@Test` heartbeat avec trades → payload a les 3 champs |
| **N+2.3** | `RunManager.java` (startRun check) | — `startRun()` rejette si même strategyId + symbol + mode déjà RUNNING<br/>— `force=true` override<br/>— Émet `DUPLICATE_RUN` event | `@Test` 2 starts → 2nd rejeté<br/>`@Test` force=true → 2nd accepté |
| **N+2.4** | `RunManager.java` (ReentrantLock per strategyId) | — Lock par strategyId, pas global<br/>— Timeout 5s pour acquire<br/>— Si timeout → IllegalStateException | `@Test` 2 startRun simultanés → 1 exécuté, 1 exception |
| **N+2.5** | `ControlPlaneServer.java` (nouvelle route) | — `GET /api/broker/health` retourne JSON : connected, lastResponse, lastFailure, uptimeSeconds<br/>— Temps de réponse < 50ms | `curl` test |
| **N+2.6** | `ControlSummaryService.java` (buildSummary enrichi) | — Chaque run item a `lastTradeAt` (timestamp dernier FILL/REJECT)<br/>— null si aucun trade | `@Test` run avec FILL → lastTradeAt = timestamp FILL |
| **N+2.7** | `log4j2.xml` (pattern avec MDC)<br/>`HeartbeatEvents.java`/`BrokerRunExecutor.java` (MDC puts) | — Logs contiennent [runId=..., strategyId=..., stale=true/false]<br/>— Recherchable via `grep stale=true` | Vérification manuelle : grep sur log |
| **N+2.8** | `TradingTuiMain.java` (nouvelle commande `/health`) | — `/health` affiche : broker OK/FAIL, DB status, N runs actives, dernier heartbeat<br/>— Temps de réponse < 1s | Test manuel dans TUI |
| **N+2.9** | `RunRecord.java` (enum Status)<br/>`RunManager.java` (markCompleted→markRetired)<br/>`BMAD_SPRINT.md` (màj status si dans le board) | — `RunRecord.Status` = CREATED, RUNNING, PAUSED, RETIRED<br/>— `COMPLETED`/`FAILED`/`ARCHIVED` remplacés par `RETIRED`<br/>— Les runs existantes en COMPLETED/FAILED sont migrées à RETIRED au premier démarrage<br/>— Toute référence à `markCompleted()`/`markFailed()` dans le code est migrée | `@Test` CREATED→RUNNING→PAUSED→RETIRED<br/>`@Test` migration : run COMPLETED existante → lue comme RETIRED |

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

### Détails d'implémentation — Epic N+3

| Story | Fichiers cibles | Critères d'acceptation | Tests |
|-------|----------------|------------------------|-------|
| **N+3.1** | Nouveau `RunRecordStore.java` dans `trading-runtime`<br/>Migration SQL dans les ressources | — Table `run_records` créée avec toutes les colonnes<br/>— INSERT/UPDATE/DELETE fonctionnels<br/>— Migration backward compatible | `@Test` CRUD complet |
| **N+3.2** | `RunManager.java` (remplacement ConcurrentHashMap)<br/>Nouveau `SqliteRunRecordStore.java` | — RunManager utilise SqliteRunRecordStore comme backing store<br/>— Cache in-memory en lecture, écriture synchrone SQLite<br/>— AtomicRead : lire de mem, écrire sur DB<br/>— Timeout écriture : 500ms max | `@Test` insert + restart mock → données relues<br/>`@Test` 1000 runs → < 2s |
| **N+3.3** | `ControlPlaneMain.java` (startup hook) | — Au startup, query run_records WHERE status=RUNNING ou PAUSED<br/>— Reconstruit OandaStreamingExecutor avec config stockée<br/>— Resume streaming<br/>— Les runs RETIRED ne sont pas restaurées | `@Test` 2 runs RUNNING en DB → toutes les 2 restaurées<br/>`@Test` RETIRED → pas restaurée |
| **N+3.4** | `StaleRunWatchdog.java` (reconnect-first)<br/>identique N+1.4, plus persistance du restartCount dans run_records | — Watchdog persiste restartCount dans run_records<br/>— 3 restart/h max<br/>— Reset restartCount après 1h sans restart<br/>— Si reconnect OK → restartCount pas incrémenté | `@Test` 3 restart/h → 4e refusé<br/>`@Test` 1h sans restart → reset |
| **N+3.5** | `SqliteEventStore.java` (transaction)<br/>TradeStore (transaction) | — event.append() et trade INSERT dans la même transaction SQLite<br/>— Si l'un échoue → ROLLBACK des deux<br/>— Pas de phantom events sans trade ou trade sans event | `@Test` insert events + trades dans transaction → tout ou rien |

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

### Détails d'implémentation — Epic N+4

| Story | Fichiers cibles | Critères d'acceptation | Tests |
|-------|----------------|------------------------|-------|
| **N+4.1** | `OandaBroker.java` (tous les appels API) | — Chaque connect/disconnect/reconnect/submitOrder loggué avec : [runId=..., symbol=..., mode=..., durationMs=N]<br/>— Pas de données sensibles (token, password) dans les logs | Vérification manuelle des logs |
| **N+4.2** | `RunEventType.java` (nouveaux types)<br/>`SqliteEventStore.java` | — 6 nouveaux types : BROKER_CONNECT, BROKER_DISCONNECT, RECONNECT_ATTEMPT, RECONNECT_FAILURE, RATE_LIMIT_TRIGGERED, STALE_PRICE_DETECTED | `@Test` chaque nouveau type s'écrit et se lit |
| **N+4.3** | `ControlPlaneServer.java` (nouvelle route) | — `GET /api/events/{runId}/audit` retourne les events filtrés par type<br/>— Paginé, trié par sequence<br/>— Filtre optionnel ?type=CONNECTION,RATE_LIMIT | `curl` test |
| **N+4.4** | `log4j2.xml` (RollingFileAppender) | — Max 10 fichiers × 100MB<br/>— Compression .gz des fichiers archivés<br/>— Pattern : `%d{ISO8601} [%t] %-5p %c - %m%n`<br/>— 7 jours de rétention | Vérification : ls -la logs/ |

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

### Détails d'implémentation — Epic N+5

| Story | Fichiers cibles | Critères d'acceptation | Tests |
|-------|----------------|------------------------|-------|
| **N+5.1** | `ControlPlaneMain.java` (startup hook)<br/>EventStore + TradeStore (count queries) | — Au startup, pour chaque run complétée : count(FILL events) vs count(trades)<br/>— Émet `DATA_DIVERGENCE` si mismatch<br/>— Ignore les runs RUNNING (trades peuvent arriver après startup) | `@Test` 5 events FILL, 4 trades → DATA_DIVERGENCE émis<br/>`@Test` matching → pas d'event |
| **N+5.2** | `SqliteBacktestRunStore.java`<br/>`SqliteEventStore.java` (initSchema) | — `PRAGMA integrity_check` après chaque connection init<br/>— Si échec → INTEGRITY_FAILURE event, log ERROR<br/>— Flag `dbCorrupted: true` dans `/api/health` | `@Test` DB corrompue → integrity_check échoue<br/>`@Test` DB saine → ok |
| **N+5.3** | Nouveau `PaperTradingSurvivabilityTest.java` dans trading-runtime | — Harness strategy + RunManager.start() → attendre trades → close store → clear HashMap → reconstruct → assert trades survivent | `mvn test -pl trading-runtime -Dtest=PaperTradingSurvivabilityTest` ✅ |
| **N+5.4** | Nouveau `ReconciliationScheduler.java` dans trading-runtime<br/>ControlSummaryService (nouvelle clé `reconciliation`) | — Tourne toutes les 6h<br/>— Query trades table + broker positions → compute diff<br/>— Écrit dans `/control/summary` sous `reconciliation`<br/>— Format : {lastRun, matched, unmatchedLocal, unmatchedBroker, staleBrokerPositions} | `@Test` 2 trades match → matched=2<br/>`@Test` 1 trade local sans position → unmatchedLocal=1 |
| **N+5.5** | `RunManager.java` (reject rate monitor)<br/>NotificationService (Telegram/Discord) | — Fenêtre glissante 1h pour ORDER_REJECT rate<br/>— Seuil >30% → HIGH_REJECT_RATE event<br/>— Stale >5min → notification Telegram<br/>— Seuils configurables via config.yaml | `@Test` 40/100 rejets → pas d'alerte<br/>`@Test` 35/100 rejets → alerte |
| **N+5.6** | Nouvelle route dans ControlPlaneServer | — `GET /api/diagnostics/integrity` : (1) integrity_check, (2) event/trade match pour 10 dernières runs, (3) broker connectivity<br/>— Retourne {checks: [{name, passed, durationMs}], overall: PASS/FAIL}<br/>— Timeout max 10s | `curl` test |
| **N+5.7** | TradeStore (timer autour de chaque INSERT) | — Timer avant/après chaque INSERT<br/>— Log WARN si >100ms<br/>— p50/p95/p99 dans `/control/summary` sous dbLatency.trades.insert | Vérification manuelle des logs |
| **N+5.8** | Nouveau `TradeArchiver.java` (cron job) | — Purge trades >90 jours (configurable)<br/>— Export CSV avant purge dans `data/archive/trades/`<br/>— Log `ARCHIVED` event avec count<br/>— Index sur `created_at` nécessaire | `@Test` purger trades >90j → 0 trades restants |

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
| **N+6.10** | Extract TradeReconstructor to shared utility in trading-core | S | N.2 | Extract the trade reconstruction logic from `ControlPlaneServer.reconstructTradesFromFills()` and `ControlSummaryService.calculatePnLMetrics()` into a shared `TradeReconstructor` utility. Also add `SharpeRatio.of(trades)`, `ProfitFactor.of(trades)`, `WinLossRatio.of(trades)`, `MaxDrawdown.of(trades)` static methods |
| **N+6.11** | Add ComparisonEngine — pure computation layer | M | N+6.10 | Pure-function engine: input = `(BacktestRunDetails, List<Trade>)`, output = `List<DimensionComparison>`. Computes Sharpe delta, PF delta, avg trade PnL delta, equity curve Pearson correlation, trade PnL distribution similarity (Kolmogorov-Smirnov), time-normalized trade frequency |
| **N+6.12** | Add equity curve correlation comparison | S | N+6.11 | Downsample backtest equity curve to match live period length, compute Pearson correlation. Thresholds: r<0.5 → REVIEW, r<0.3 → PAUSE. Include scatter plot data in response |
| **N+6.13** | Add trade PnL distribution comparison (KS test) | S | N+6.11 | Kolmogorov-Smirnov test comparing trade PnL distributions from backtest vs paper. Detect if the distribution of outcomes has fundamentally changed, even if averages look similar. p<0.05 → REVIEW, p<0.01 → PAUSE |
| **N+6.14** | Add commission & slippage drift tracking | XS | N+6.11 | Compare `totalCommission` and `totalSlippage` from backtest vs paper. If actual costs are significantly higher (>2×), flag as `COST_DRIFT` — could indicate market regime shift or broker change |

**Total Epic N+6**: ~5 jours

---

## Epic N+7 — Reliability Documentation & Operational Runbook (Epic 38)

**Objectif** : Documenter les procédures opérationnelles, les runbooks d'incident, les SLOs, et les checklists pour qu'un opérateur (toi) puisse lancer, monitorer et dépanner le platform de trading en toute confiance.

**État des lieux** : Un `docs/prop-shop-runbook.md` existe déjà (focus promote gate, 126 lignes). Mais il manque : procédures de récupération d'incident, définitions de sévérité, SLOs, dashboard monitoring, checklists pré-run, recovery après crash.

### User Stories (Valeur Métier)

| # | As a [role]... | Value |
|---|----------------|-------|
| US-N+7.1 | Operator (you) | I want a step-by-step runbook for every known failure mode so I don't have to debug under pressure | Fast incident response |
| US-N+7.2 | Operator (you) | I want to know the platform is healthy at a glance — SLOs, dashboards, alerts | Trust without constant checking |
| US-N+7.3 | Operator (you) | I want a pre-flight checklist before starting a new paper/live run | Avoid mistakes |
| US-N+7.4 | Operator (you) | I want a weekly review ritual to catch problems early | Preventative maintenance |
| US-N+7.5 | Developer (you) | I want clear incident severity definitions so I know what's urgent vs routine | Prioritize correctly |

### Coding Stories

| # | Story | Effort | Dépend de | Description |
|---|-------|--------|-----------|-------------|
| **N+7.1** | Write Reliability SLOs document | S | — | Document `docs/reliability-slos.md`. Define SLOs : (1) Trade persistence : 100% of trades survive restart (Epic N), (2) Broker connectivity : < 30s reconnect after network drop (Epic N+1), (3) Stale detection : any stale run detected within 2min (Epic N+2), (4) Run recovery : 100% of running strategies restored on restart (Epic N+3). Each SLO has : metric definition, measurement method, current status (baseline), target |
| **N+7.2** | Write Incident Severity Matrix | S | — | Document `docs/incident-severity.md`. Define P0-P3 : **P0** = trade data loss, broker position desync, platform crash on startup. **P1** = stale run not detected, reconnect loop, duplicate run. **P2** = delayed heartbeat, slow trades query. **P3** = cosmetic, missing MDC log field. Each level has : response time, notification method (Telegram/Discord), who handles it |
| **N+7.3** | Write Pre-Flight Checklist for new runs | S | — | Document `docs/run-preflight-checklist.md`. Before starting any paper/live run : (1) Broker account connected ? `GET /api/broker/health`, (2) Event store writable ? (check events.db + trades.db exist), (3) Strategy has backtest baseline ? (parameterHash exists), (4) No duplicate run for same strategyId+symbol+mode ?, (5) Historical data available for symbol ?, (6) Kill switch not active ?, (7) Equity sufficient for position sizing ? |
| **N+7.4** | Write Incident Response Runbooks (4 failure modes) | M | N+7.2 | Document `docs/runbooks/`. One markdown file per failure mode : (1) `broker-disconnect.md` — symptoms (ORDER_REJECT, BROKER_DISCONNECT event), diagnostics (check `/api/broker/health`, check OANDA status page), recovery (wait for auto-reconnect or manual `POST /api/runs/{runId}/reconnect`), verification (confirm trades resume), (2) `stale-run.md`, (3) `db-corruption.md`, (4) `oanda-rate-limit.md`. Each runbook has : symptoms, severity, diagnostics steps, recovery steps, verification steps |
| **N+7.5** | Write Daily/Weekly Operations Review | S | — | Document `docs/weekly-review.md`. Daily : (1) Check `/control/summary` — any stale ? any gaps ? any daily DD breach ?, (2) Check Discord alert channel for overnight issues. Weekly : (1) Review reconciliation report (N+5.4), (2) Review drift comparison for each running strategy (N+6.6), (3) Review error logs for unknown exceptions, (4) Verify all crons ran successfully, (5) Update decision log |
| **N+7.6** | Write Platform Recovery Runbook | M | N+3.3 | Document `docs/runbooks/platform-recovery.md`. Step-by-step after crash or restart : (1) Verify events.db + trades.db files exist and are readable, (2) Start control plane → check logs for auto-restore of RUNNING strategies, (3) Verify each restored strategy : `GET /api/runs/{runId}` shows status=RUNNING, (4) Verify broker connection : `GET /api/broker/health` returns connected=true, (5) Run reconciliation : check unmatched trades, (6) Confirm no duplicate runs created by watchdog |
| **N+7.7** | Write Run Promotion Playbook (extend existing) | S | N+7.5 | Extend `docs/prop-shop-runbook.md` with : (1) promote BACKTEST→PAPER (check parameter match, check backtest metrics vs thresholds), (2) promote PAPER→LIVE (check 30-day observation, check reconciliation clear, check drift comparison GREEN, check kill switch inactive), (3) rollback procedure if LIVE run behaves unexpectedly |
| **N+7.8** | Write Operator Dashboard Guide | S | N+2.5, N+2.6 | Document `docs/operator-dashboard.md`. Explain each field in `/control/summary` response : what each signal means, what GREEN/YELLOW/RED means, what action to take for each state. Include example responses + screenshots |

**Total Epic N+7**: ~4 jours

---

## Updated Priority / Build Order

| Phase | Epic | Effort | Why first |
|-------|------|--------|-----------|
| **4.1** | Epic N — Trade-Level Audit & Persistence | ~4j | Foundation — everything else depends on structured trade data |
| **4.2** | Epic N+1 — Connection Resilience | ~5j | Second — stops data loss from connectivity failures |
| **4.3** | Epic N+2 — Monitoring & Observability | ~3j | Third — surface the data that Epics N and N+1 produce |
| **4.4** | Epic N+3 — Stateful Run Recovery | ~4j | Fourth — requires Epic N (trades table) and N+1 (reconnect) |
| **4.5** | Epic N+4 — Logging & Diagnostics | ~1.5j | Can start in parallel with 4.2 (independent) |
| **4.6** | Epic N+5 — Verification & Malfunction Detection | ~3j | Proves everything above actually works |
| **4.7** | Epic N+6 — BT vs Paper Drift Comparison | ~5j | Requires trades table (N) and event log (N+4) |
| **4.8** | **Epic N+7 — Reliability Doc & Runbook** | ~4j | Last — documents everything built in N through N+6 |

**Total revised**: ~29.5 jours

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
