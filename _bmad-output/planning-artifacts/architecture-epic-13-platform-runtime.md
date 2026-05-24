---
stepsCompleted: [1, 2, 3, 4, 5, 6, 7, 8]
inputDocuments:
  - _bmad-output/brainstorming/brainstorming-session-2026-05-23-1800.md
  - _bmad-output/project-context.md
  - _bmad-output/implementation-artifacts/12-4-run-context-unified-runtime.md
  - _bmad-output/implementation-artifacts/12-5-run-event-stream-jsonl.md
  - _bmad-output/implementation-artifacts/12-6-paper-runner-stub.md
  - docs/specs.md
workflowType: architecture
project_name: Trading Bridge
user_name: Martin Fournier
date: '2026-05-23'
epic: 13
title: Platform Runtime — Control Plane, TUI, Laravel Dashboard
---

# Architecture Decision Document — Epic 13

_Plateforme unifiée backtest → paper → production avec TUI (atelier) et dashboard Laravel (salle de contrôle)._

## 1. Contexte

### État actuel (post Epic 12)

| Composant | Statut |
|-----------|--------|
| `StrategyCatalog` | 21 stratégies, CLI unifié |
| `RunContext` | BACKTEST + PAPER (stub) |
| `RunEvent` JSONL v1 | `--json` sur stdout |
| Golden backtest | Contrat CI (8760 bars, 63 trades) |
| Live / broker | Epic 4 backlog |

### Vision produit (brainstorming 2026-05-23)

**JTBD :** *Idée → backtest → paper → prod sans réécrire ni douter que les chiffres signifient la même chose.*

**Surfaces :**
- **CLI** — CI, scripts, dev rapide (existant)
- **TUI** — atelier : créer, backtester, promouvoir, logs streamés
- **Laravel** — contrôle : positions, PnL, alertes, kill switch

## 2. Principes architecturaux (immuable)

1. **Java engine = source de vérité** — stratégies, ordres, état, événements
2. **Une stratégie, un artefact** — `Strategy` + entrée catalog, versionnée
3. **Un moteur, trois runtimes** — `BacktestEngine`, `PaperExecutor` (stub puis live), `LiveExecutor`
4. **État observable, actions impératives** — API REST + WebSocket ; kill = commande HTTP
5. **Clients thin** — TUI et Laravel ne calculent pas le PnL ; ils projettent l'état Java
6. **Boring tech** — Javalin ou Spring Boot minimal, SQLite/PG event log, pas de microservices

## 3. Vue d'ensemble

```
┌─────────────────────────────────────────────────────────────────┐
│                    trading-runtime (NEW module)                  │
│  ControlPlaneServer (HTTP + WS)                                  │
│  RunManager — démarre/arrête RunContext, persiste events         │
│  DeploymentStore — backtest/paper/live par strategyId            │
│  EventStore — append-only (SQLite dev, PG prod)                  │
└────────────────────────────┬────────────────────────────────────┘
                             │
         ┌───────────────────┼───────────────────┐
         ▼                   ▼                   ▼
  trading-backtest     trading-strategies   trading-broker (Epic 4)
  RunContext           StrategyCatalog      LiveExecutor
  RunEvent             PaperExecutor*
  BacktestEngine

  * PaperExecutor stub → live paper Epic 4

┌──────────────┐   REST/WS    ┌──────────────┐
│  TUI client  │◄────────────►│ Control Plane│
│  (JLine3)    │   JSONL tail │   (Java)     │
└──────────────┘              └──────┬───────┘
                                     │ REST poll + WS
                              ┌──────▼───────┐
                              │ Laravel app  │
                              │ (dashboard)  │
                              └──────────────┘
```

## 4. Décisions architecturales

### ADR-13-01 — Control Plane en Java (pas Laravel)

**Décision :** Nouveau module `trading-runtime` expose HTTP REST + WebSocket. Laravel est **client read/command**, jamais propriétaire de l'état trading.

**Rationale :** Une seule source de vérité ; pas de logique ordre dupliquée en PHP ; aligné avec `RunContext` existant.

**Stack :** Javalin 6.x (léger, embarqué) ou Spring Boot si besoin auth/config plus tard.

### ADR-13-02 — RunEvent comme contrat inter-surfaces

**Décision :** Étendre JSONL v1 → bus persistant + WebSocket broadcast. Schéma versionné ; breaking change = `schemaVersion: 2`.

**Events MVP Epic 13 :**
- Existants : `RUN_STARTED`, `RUN_ENDED`, `ERROR`
- Ajouts : `DEPLOYMENT_CHANGED`, `HEARTBEAT`, `POSITION_UPDATE`, `KILL_REQUESTED`

**Transport :**
- CLI/TUI batch : stdout JSONL (inchangé)
- Control plane : WebSocket `/ws/runs/{runId}` + GET `/api/runs/{runId}/events`

### ADR-13-03 — TUI Java-first (JLine3)

**Décision :** TUI en Java dans `trading-examples` ou module `trading-tui`, consommateur du control plane local.

**Rationale (Sally vs Winston) :** Réutilise `StrategyCatalog`, `RunContext`, pas de FFI Rust ; prototypage rapide. Rust revisité si perf terminal insuffisante.

**UX MVP :**
- Barre statut persistante (stratégie, mode, capital, drawdown)
- Slash commands : `/list`, `/backtest`, `/paper`, `/promote`, `/status`
- Streaming RunEvents (comme Claude Code token stream)

### ADR-13-04 — Laravel thin dashboard

**Décision :** App Laravel séparée (`dashboard/` ou repo adjacent), poll `GET /api/strategies` + WS proxy.

**MVP v1 :**
- 3 complications : exposition, PnL jour, connexion broker
- Timeline alertes (events filtrés)
- Kill switch → `POST /api/strategies/{id}/kill`

**Hors MVP :** auth multi-user, graphiques avancés, multi-broker.

### ADR-13-05 — Pipeline promote avec gates

**Décision :** Transitions backtest → paper → live passent par **checks** automatisés (inspiré GitHub Actions).

| Gate | Condition |
|------|-----------|
| → paper | golden backtest vert OU run local `--json` ENDED avec métriques min |
| → live | paper N jours sans crash + drawdown dans bande |

Stockage : `DeploymentRecord { strategyId, mode, artifactHash, promotedAt, checks[] }`.

### ADR-13-06 — Dépendance module corrigée (refactor 13.1)

**Décision :** Extraire `RunContext.backtest(String id…)` vers une factory dans `trading-examples` ; `trading-backtest` ne dépend plus de `trading-strategies` compile.

**Rationale :** Review 12.3–12.6 — couplage moteur ↔ catalog acceptable temporairement ; Epic 13.1 le corrige.

## 5. API Control Plane (MVP)

### REST

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/health` | Liveness + version |
| GET | `/api/strategies` | Liste catalog + mode déployé |
| POST | `/api/runs` | `{ strategyId, symbol, mode, barsSource }` → runId |
| GET | `/api/runs/{runId}` | Statut + dernier ENDED payload |
| GET | `/api/runs/{runId}/events` | JSONL paginé |
| POST | `/api/strategies/{id}/promote` | `{ targetMode: PAPER\|LIVE }` + gates |
| POST | `/api/strategies/{id}/kill` | Flatten + stop |

### WebSocket

- `/ws/runs/{runId}` — stream RunEvent temps réel
- `/ws/dashboard` — agrégat positions + alertes (fan-in côté serveur)

## 6. Structure modules Maven (cible)

```
trading-core
trading-backtest        ← RunContext, RunEvent (sans catalog)
trading-strategies
trading-data
trading-broker          ← Epic 4
trading-runtime         ← NEW: ControlPlane, RunManager, EventStore
trading-tui             ← NEW (optionnel): JLine3 client
trading-examples        ← RunBacktest CLI, catalog wiring
```

**Nouveau `trading-runtime` dépend de :** `trading-backtest`, `trading-strategies`, `trading-data`.

## 7. Epic 13 — Stories proposées

| Story | Titre | Priorité |
|-------|-------|----------|
| 13.1 | Découpler RunContext du catalog (factory examples) | P0 |
| 13.2 | EventStore SQLite + replay API | P0 |
| 13.3 | ControlPlane HTTP (Javalin) + health/strategies/runs | P0 |
| 13.4 | WebSocket RunEvent broadcast | P1 |
| 13.5 | Promote gates + DeploymentStore | P1 |
| 13.6 | TUI v1 — JLine3 slash commands + stream | P1 |
| 13.7 | Laravel dashboard v1 — complications + kill | P1 |
| 13.8 | Heartbeat + stale data detection | P2 |

## 8. Risques et atténuation

| Risque | Atténuation |
|--------|-------------|
| Paper stub = backtest (fausse confiance) | Label `PAPER_STUB` dans events ; doc ; Epic 4 live paper |
| SLF4J pollue stdout `--json` | Control plane redirige logs vers fichier ; CLI `--json` set log level WARN |
| Laravel second codebase | Thin client ; zéro logique trading en PHP |
| Scope creep TUI Claude | MVP slash commands ; pas de LLM intégré en 13.6 |

## 9. Critères de succès Epic 13

- [ ] `POST /api/runs` lance un backtest et stream events via WS
- [ ] TUI `/backtest LondonOpenRangeBreakout EUR_USD 2012` affiche stream
- [ ] Laravel affiche PnL/exposition en <2s (poll ou WS)
- [ ] Kill switch depuis Laravel arrête le run Java
- [ ] Golden backtest reste vert en CI

## 10. Références

- Brainstorming : `_bmad-output/brainstorming/brainstorming-session-2026-05-23-1800.md`
- Epic 12 pipeline : stories 12.3–12.6
- Code review 12.3–12.6 : `_bmad-output/implementation-artifacts/deferred-work.md`
