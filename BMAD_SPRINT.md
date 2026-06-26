# BMAD Sprint — Paper Trading Reliability & Monitoring

> **Sprint 7** — 2026-06-26
> **Martin Fournier** — Product Owner

---

## 🎯 Objectif du Sprint

Restaurer la confiance dans le paper trading en ajoutant :
- Une table `trades` persistante pour que l'historique survive au restart
- La connexion broker avec keepalive et auto-reconnect
- Un monitoring en temps réel (trades, PnL, heartbeat, health)

**Problème racine** : Tout l'état des runs (`RunRecord`) est en mémoire dans un `ConcurrentHashMap`. Les events survivent en SQLite mais les trades et métriques ne sont pas requêtables sans replay complet.

---

## 📋 Sprint Backlog

### Epic 31 — Trade-Level Audit & Persistence (4j)

| # | Story | Effort | Statut |
|---|-------|--------|--------|
| 31.1 | Create `trades` SQLite table | S | 📝 |
| 31.2 | Migrate event replay → write-time trade extraction | M | 📝 |
| 31.3 | Fix `totalTrades=0` for OANDA streaming | XS | 📝 |
| 31.4 | `GET /api/trades` REST endpoint | S | 📝 |
| 31.5 | `GET /api/trades/summary` endpoint | XS | 📝 |
| 31.6 | Rebuild positions from trades table after restart | M | 📝 |
| 31.7 | Partial fill detection (PARTIAL_FILL event) | S | 📝 |

### Epic 32 — Connection Resilience & Keepalive (5j)

| # | Story | Effort | Statut |
|---|-------|--------|--------|
| 32.1 | REST keepalive heartbeat for OandaBroker (30s) | M | 📝 |
| 32.2 | Implement `OandaBroker.reconnect()` (currently dead code) | S | 📝 |
| 32.3 | Connection-state event stream (CONNECTION events) | S | 📝 |
| 32.4 | StaleRunWatchdog: reconnect before restart | M | 📝 |
| 32.5 | Exponential backoff on broker reconnect | XS | 📝 |
| 32.6 | Position reconciliation after reconnect | M | 📝 |
| 32.7 | OANDA API rate-limit guard | S | 📝 |
| 32.8 | Market hours / weekend detection | S | 📝 |
| 32.9 | Stale price detection | XS | 📝 |

### Epic 33 — Monitoring & Observability (3j)

| # | Story | Effort | Statut |
|---|-------|--------|--------|
| 33.1 | Fix `totalTrades` in OANDA BacktestResult | XS | 📝 |
| 33.2 | Enrich heartbeat events with trade metadata | S | 📝 |
| 33.3 | Duplicate-run prevention guard | S | 📝 |
| 33.4 | Run-startup race condition lock | XS | 📝 |
| 33.5 | `GET /api/broker/health` endpoint | XS | 📝 |
| 33.6 | `lastTradeAt` in `/control/summary` | XS | 📝 |
| 33.7 | Log4j structured MDC markers | XS | 📝 |
| 33.8 | TUI `/health` command | S | 📝 |
| 33.9 | Fix RunRecord status model: RUNNING / PAUSED / RETIRED | S | 📝 |

### Epic 34 — Stateful Run Recovery (4j)

| # | Story | Effort | Statut |
|---|-------|--------|--------|
| 34.1 | Create `run_records` SQLite table | M | 📝 |
| 34.2 | Migrate RunManager to DB-backed storage | L | 📝 |
| 34.3 | Auto-restore runs on control-plane startup | M | 📝 |
| 34.4 | StaleRunWatchdog: reconnect-first, then clean restart | M | 📝 |
| 34.5 | Crash-safe SQLite transaction boundaries | S | 📝 |

### Epic 35 — Logging & Diagnostics Infrastructure (1.5j)

| # | Story | Effort | Statut |
|---|-------|--------|--------|
| 35.1 | Structured logging to OandaBroker methods | XS | 📝 |
| 35.2 | New audit event types in EventStore | S | 📝 |
| 35.3 | `GET /api/events/{runId}/audit` endpoint | XS | 📝 |
| 35.4 | Log4j2 RollingFileAppender config | S | 📝 |

### Epic 36 — Verification & Malfunction Detection (3j)

| # | Story | Effort | Statut |
|---|-------|--------|--------|
| 36.1 | Start-of-day event/trade reconciliation | M | 📝 |
| 36.2 | SQLite integrity_check on every connection open | XS | 📝 |
| 36.3 | Integration test: kill and restart (survivability) | M | 📝 |
| 36.4 | Cron: daily reconciliation report (every 6h) | M | 📝 |
| 36.5 | Alerting: high reject rate & stale runs via Telegram | S | 📝 |
| 36.6 | Self-diagnostic endpoint | S | 📝 |
| 36.7 | Trade write-latency monitoring | XS | 📝 |
| 36.8 | Data retention & archival policy (90 days) | S | 📝 |

---

## 🔷 Bugs Identifiés (Code-Inspectés)

| # | Bug | Fichier | Sévérité |
|---|-----|---------|:--------:|
| B1 | `totalTrades=0` hardcodé pour OANDA streaming | `RunManager.java:473-477` | 🔴 |
| B2 | RunRecords en `ConcurrentHashMap` — perdus au restart | `RunManager.java:108-112` | 🔴 |
| B3 | Aucune table `trades` — events = JSON blobs opaques | `SqliteEventStore.java:142-156` | 🔴 |
| B4 | `OandaBroker.connect()` one-shot, `reconnect()` jamais appelé | `OandaBroker.java:41-48` | 🟠 |
| B5 | StaleRunWatchdog crée des nouvelles runs, perd l'état | `StaleRunWatchdog.java:79-96` | 🟠 |
| B6 | Pas de garde anti-run dupliquée | `RunManager.java:352-371` | 🟠 |
| B7 | Stream watchdog reconnect mais REST reste mort | `OandaStreamingClient.java:129-143` | 🟡 |
| B8 | Aucun logging structuré pour les events de trade | `OandaBroker.java` | 🟡 |
| B9 | `COMPLETED` n'a pas de sens en paper trading | `RunRecord.Status` | 🟠 |
| B10 | Noms de stratégies dupliqués dans l'historique | `RunManager.java` | 🟡 |

---

## ✅ Build Order

| Phase | Epic | Effort | Dépend de |
|-------|------|:------:|-----------|
| 4.1 | Epic 31 — Trade-Level Audit & Persistence | 4j | — |
| 4.2 | Epic 32 — Connection Resilience | 5j | Epic 31 |
| 4.3 | Epic 33 — Monitoring & Observability | 3j | Epic 31 |
| 4.4 | Epic 34 — Stateful Run Recovery | 4j | Epic 31, 32 |
| 4.5 | Epic 35 — Logging & Diagnostics | 1.5j | — (parallèle) |
| 4.6 | Epic 36 — Verification & Malfunction Detection | 3j | Epic N, N+1 |

**Total**: ~20 jours

---

## 🧪 Définition de Done

- [ ] `trades` table créée avec les colonnes typées (pnl, commission, slippage)
- [ ] `totalTrades` n'est plus jamais 0 pour les runs OANDA
- [ ] Les trades survivent à un restart du control plane
- [ ] `GET /api/trades` retourne les trades sans replay d'events
- [ ] `OandaBroker` a un keepalive + reconnect fonctionnel
- [ ] `StaleRunWatchdog` tente reconnect avant restart
- [ ] Aucun run dupliqué possible (par `strategyId + symbol + mode`)
- [ ] `GET /api/broker/health` retourne l'état réel de la connexion
- [ ] Le status model est `RUNNING / PAUSED / RETIRED`

---

## 📂 Références

- Epics & Stories complet : `_bmad-output/planning-artifacts/epics-and-stories-paper-trading-reliability.md`
- Rapport Joplin : `02-Projects/Trading robot management system/03-Decisions`
