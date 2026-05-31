---
stepsCompleted: [1, 2, 3, 4]
validationStatus: passed-with-notes
validatedAt: 2026-05-24
inputDocuments:
  - _bmad-output/planning-artifacts/prds/prd-Trading Bridge-2026-05-24/prd.md
  - _bmad-output/planning-artifacts/prds/prd-Trading Bridge-2026-05-24/addendum.md
  - _bmad-output/planning-artifacts/architecture-epic-13-platform-runtime.md
  - _bmad-output/planning-artifacts/adr-13-distributed-platform.md
approvedStructure: "Epics 13-20, Epic 17 Phase A puis Phase B, ordre 13→14→15→16→17-A"
legacyReference:
  - _bmad-output/planning-artifacts/epics-legacy-sprint-plan.md
---

# Trading Bridge - Epic Breakdown

## Overview

Ce document décompose les exigences du PRD final (2026-05-24), des ADR Epic 13 / plateforme distribuée et des spécifications dashboard (architecture) en epics et stories implémentables. Structure approuvée après party mode (2026-05-24) : epics 13–20, Epic 17 en Phase A (observabilité) puis Phase B (actions).

**Ordre de livraison recommandé :** 13 → 14 → 15 → 16 → 17-A → 17-B → 18 → 19 → 20

## Requirements Inventory

### Functional Requirements

FR1: Import StrategyQuant JForex — Martin peut importer un export SQ/JForex XML uniquement et obtenir une Strategy Java compilable (conversion complète ou partielle avec rapport de gaps explicite). Stratégie pilote : `firstSqJforx`. Realise UJ-1.

FR2: Direct Java authoring — Martin peut enregistrer une Strategy Java hand-written dans StrategyCatalog et l'exécuter via les mêmes APIs que les stratégies importées. Realise UJ-1.

FR3: AI-assisted ideation and codegen (Phase 3, DeepSeek cloud) — Martin peut demander des hypothèses et squelettes Java via DeepSeek API, soumis au même pipeline de validation. Jamais LIVE sans gates. Tag `origin: AI`, `llmProvider: deepseek`. Realise UJ-4.

FR4: Unified backtest execution — Martin peut lancer un backtest depuis Control Plane ou CLI avec sémantique de fill cohérente (MARKET @ open). Golden backtest CI vert. Events persistés avant broadcast WebSocket. Realise UJ-1.

FR5: Config snapshot immutability — Chaque run stocke un snapshot config immuable + hash SHA-256 au RUN_STARTED. `GET /api/runs/{runId}` retourne `configSnapshot` et `configHash`. Realise UJ-1, UJ-3.

FR6: Advanced validation modules (post-MVP) — Plateforme supporte validation pluggable : purged walk-forward, CPCV, stress tests, chemins synthétiques. Résultats gates stockés comme RunEvents. Realise UJ-1 (étendu).

FR7: Promote with automated gates — Martin peut promouvoir vers PAPER ou LIVE uniquement si gates passent (golden backtest, min trades, bande drawdown, 30 jours paper minimum avant LIVE). `POST /api/strategies/{id}/promote` retourne 422 avec raisons si échec. Realise UJ-2.

FR8: Paper trading on OANDA — Martin peut exécuter en mode PAPER sur OANDA demo avec events journalisés comme backtest. Promote LIVE bloqué si paper < 30 jours. Stub `PAPER_STUB` max 30 j en dev. Realise UJ-2.

FR9: Live forex execution — Martin peut exécuter LIVE sur OANDA ; ordres exécutés sur worker node, pas control plane. Kill switch stop nouveaux ordres. Single writer broker par lease (Phase 2 multi-node). Realise UJ-2.

FR10: Never lose a run — Tous les events de run persistés durablement ; redémarrage récupère statut et journal. 0 runs perdus sur 10 crash tests. Gaps de séquence signalés en API. Realise UJ-1, UJ-2.

FR11: Network state dashboard — Martin voit tous les nœuds (live > paper > backtest), fraîcheur (`lastEventAt`), alertes en < 3 s. Nœud offline = état dégradé, pas faux vert. Run actif sur nœud offline = « état inconnu ». Realise UJ-2, UJ-3.

FR12: Remote worker sync (post-MVP mono-nœud) — Workers sur PCs séparés synchronisent events vers hub central avec replay idempotent. Déduplication `(runId, eventId)`. Outbox locale replay après recovery hub. Realise UJ-2.

FR13: Lifecycle states — Martin peut définir Deployment ACTIVE, PAUSED ou RETIRED avec motif obligatoire. PAUSED rejette nouveaux runs. RETIRED terminal, données historiques conservées. Realise UJ-3.

FR14: Change history immuable — Chaque promotion, changement paramètre, action opérateur et résultat gate append au journal audit lié strategyId/deploymentId. Evidence pack inclut actions opérateur. Realise UJ-3.

FR15: Drift and performance signals — Signaux quand perf live/paper dévie de baseline backtest au-delà de seuils configurables (fenêtre 30j glissants, min 14j ou 20 trades avant signal). Recommandations HOLD / REVIEW_PARAMS / PAUSE / RETIRE. Règle composite 2 dimensions rouges → PAUSE. Realise UJ-3.

FR16: Parameter retune workflow — Martin peut forker nouvelle version config, backtester, promouvoir via gates uniquement — jamais hot-swap silencieux LIVE. Realise UJ-3.

FR17: Genetics integration — Martin peut exporter gagnants recherche génétique vers StrategyCatalog candidats soumis aux gates FR-7. Tag `origin: GENETICS` dans snapshot. Realise UJ-1.

### NonFunctional Requirements

NFR1: Temps — UTC (`Instant`) partout en logique trading ; affichage Toronto optionnel UI.

NFR2: Sécurité — Aucun credential en repo ; clés via env ou fichiers locaux ignorés git (`DEEPSEEK_API_KEY`, OANDA).

NFR3: Fiabilité — Promesse MVP « ne jamais perdre un run » prioritaire sur hub always-on.

NFR4: Observabilité — Events append-only ; UI prouve fraîcheur, jamais voyant vert sans heartbeat récent.

NFR5: Audit — OPERATOR_ACTION + config snapshot immuable par run ; chaîne causale run → ordre → fill.

NFR6: Safety — Kill switch ; pause empêche nouveaux ordres ; LIVE requiert gates ; hub passif (ne route pas ordres).

NFR7: Privacy — Stratégies et clés API restent locales ; pas de SaaS multi-tenant v1.

NFR8: Cost — Pas d'infra cloud obligatoire MVP ; SQLite + PCs existants.

NFR9: Compatibilité — RunEvent v1 schéma versionné ; breaking change = `schemaVersion: 2`.

NFR10: Déploiement — Chemins DB configurables (`RuntimeDataPaths`, env `TRADING_BRIDGE_EVENT_STORE`, `TRADING_BRIDGE_DATA_DIR`).

### Additional Requirements

- **Brownfield** — Extension modules Maven existants (`trading-runtime`, `trading-backtest`, etc.).
- **Hub passif (ADR-13-07)** — Control plane observe ; ne route pas ordres broker.
- **Invariant persist→broadcast (ADR-13-10)** — `persist(EventStore)` puis `broadcast(RunEventHub)`.
- **Contrat API v0 Salle** — `GET /control/summary`, `schemaVersion: 1`, champs additive-only.
- **RunLifecycle interface** — `RunManager` lifecycle-only ; promote/gap via collaborateurs.
- **Evidence pack (S14)** — `GET /api/runs/{runId}/export` JSONL.
- **Module `trading-node` (S15)** — Epic 18.

### UX Design Requirements

UX-DR1: Dashboard « État du réseau » — priorité live > paper > backtest.

UX-DR2: Indicateur fraîcheur — « Dernier event il y a X s » ; jamais vert sans heartbeat récent.

UX-DR3: État dégradé nœud offline — badge explicite.

UX-DR4: Run actif sur nœud offline — « état inconnu ».

UX-DR5: Laravel MVP — 3 complications + timeline alertes.

UX-DR6: Kill switch UI — confirmation + feedback immédiat.

UX-DR7: Signaux drift — HOLD / REVIEW_PARAMS / PAUSE / RETIRE avec métriques.

UX-DR8: TUI optionnel — slash commands + stream RunEvents.

UX-DR9: Hub ouvert en < 3 s — état visible sans navigation profonde.

### FR Coverage Map

FR1: Epic 14 — Import SQ JForex (`firstSqJforx`)
FR2: Epic 14 — Java authoring catalog
FR3: Epic 20 — AI DeepSeek Phase 3
FR4: Epic 13 — Backtest unifié API/CLI
FR5: Epic 13 — Config snapshot immuable
FR6: Epic 15 (MVP slice) + Epic 19 (avancé)
FR7: Epic 15 — Gates promote
FR8: Epic 16 — Paper OANDA
FR9: Epic 16 — Live OANDA worker
FR10: Epic 13 — Never lose a run
FR11: Epic 17 Phase A — Dashboard réseau
FR12: Epic 18 — Sync multi-PC
FR13: Epic 17 Phase B — ACTIVE/PAUSED/RETIRED
FR14: Epic 17 Phase B — Audit immuable
FR15: Epic 17 Phase B — Signaux drift
FR16: Epic 17 Phase B — Retune workflow
FR17: Epic 14 — Genetics → catalog

## Epic List

### Epic 13: Fondation runtime & journal durable
Martin peut lancer des backtests reproductibles via le control plane ; les runs et events survivent aux crashs avec config immuable et gaps signalés.
**FRs covered:** FR4, FR5, FR10 | **NFRs:** NFR3, NFR4, NFR10 | **Sprint:** S13

### Epic 14: Onboarding stratégies
Martin peut importer SQ/JForex, enregistrer des stratégies Java et intégrer des candidats genetics au catalog.
**FRs covered:** FR1, FR2, FR17 | **Sprint:** S13–14

### Epic 15: Validation MVP & promote gates
Martin peut promouvoir une stratégie vers PAPER/LIVE uniquement si les gates passent ; export evidence pack pour audit.
**FRs covered:** FR6 (MVP), FR7, FR14 (export) | **Sprint:** S14

### Epic 16: Exécution OANDA paper → live
Martin peut exécuter en paper OANDA demo puis LIVE sur worker local ; kill switch journalisé.
**FRs covered:** FR8, FR9 | **NFRs:** NFR2, NFR6 | **Sprint:** S14–15

### Epic 17: Salle de contrôle
Martin voit l'état du réseau en < 3 s (Phase A) puis pause/retire/retune avec signaux drift (Phase B).
**FRs covered:** FR11, FR13, FR14, FR15, FR16 | **UX-DRs:** UX-DR1–9 | **Sprint:** S15–16

### Epic 18: Plateforme distribuée
Martin peut exécuter workers sur PCs séparés avec sync idempotent et mode dégradé hub down.
**FRs covered:** FR12 | **Sprint:** S15–16

### Epic 19: Validation statistique avancée
Martin peut appliquer purged WFA, CPCV et stress tests comme gates extensibles.
**FRs covered:** FR6 (reste) | **Phase:** 2

### Epic 20: Authoring AI DeepSeek
Martin peut générer des ébauches Java via DeepSeek soumises aux mêmes gates.
**FRs covered:** FR3 | **Phase:** 3

---

## Epic 13: Fondation runtime & journal durable

Martin peut lancer des backtests reproductibles via le control plane ; les runs et events survivent aux crashs avec config immuable et gaps signalés.

### Story 13.1: Interface RunLifecycle et refactor RunManager

As a developer,
I want RunManager to implement a frozen RunLifecycle interface with lifecycle-only responsibilities,
So that promote, gap detection and deployment logic do not churn the same class.

**Acceptance Criteria:**

**Given** the existing `RunManager` in `trading-runtime`
**When** `RunLifecycle` is introduced with `register`, `start`, `stop`, `pause`, `resume`, `archive`, `get`, `list`
**Then** `RunManager` is the sole implementation
**And** `RunManagerTest` characterisation tests pass before and after refactor
**And** no promote or gap logic remains in `RunManager`

### Story 13.2: Chemins runtime configurables et stores SQLite

As a Martin (operator),
I want event and run data stored in configurable persistent paths,
So that redeploys do not wipe my journal.

**Acceptance Criteria:**

**Given** env `TRADING_BRIDGE_EVENT_STORE` or `TRADING_BRIDGE_DATA_DIR` (or defaults under `data/runtime/`)
**When** the control plane starts
**Then** `RuntimeDataPaths` resolves a writable SQLite path
**And** `SqliteEventStore` and run stores use that path
**And** `RuntimeDataPathsTest` passes

### Story 13.3: Snapshot config immuable au RUN_STARTED

As a Martin,
I want each run to capture an immutable config snapshot and SHA-256 hash at start,
So that I can audit what parameters were used.

**Acceptance Criteria:**

**Given** a run is registered with strategy parameters
**When** `RUN_STARTED` is emitted
**Then** `RunConfigSnapshot` is persisted and never mutated for that run
**And** `GET /api/runs/{runId}` returns `configSnapshot` and `configHash`
**And** post-run catalog edits do not alter the stored snapshot

### Story 13.4: Invariant persist avant broadcast

As a Martin,
I want every run event persisted before WebSocket broadcast,
So that subscribers never see events that could be lost on crash.

**Acceptance Criteria:**

**Given** a run emits a `RunEvent`
**When** the event is processed by the control plane
**Then** `EventStore.append` completes before `RunEventHub.broadcast`
**And** `BroadcastingEventStoreTest` (or equivalent) verifies ordering
**And** golden backtest CI remains green

### Story 13.5: Détection de gaps et exposition API

As a Martin,
I want sequence gaps detected and exposed in the API,
So that I never assume a complete journal without proof.

**Acceptance Criteria:**

**Given** a run with missing sequence numbers in the event store
**When** `EventGapDetector` runs or `GET /api/runs/{runId}` is called
**Then** gaps are returned with `fromSequence`, `toSequence`, `severity`
**And** `EventGapDetectorTest` covers contiguous and gapped sequences
**And** empty gaps array means provably contiguous (not assumed)

### Story 13.6: Control plane — lancer et consulter des runs

As a Martin,
I want to start backtests via HTTP and query run status,
So that I do not need Maven CLI for routine runs.

**Acceptance Criteria:**

**Given** the control plane is running
**When** I `POST /api/runs` with `{ strategyId, symbol, mode: BACKTEST, barsSource }`
**Then** I receive a `runId` and the run executes with MARKET @ open fills
**And** `GET /api/runs/{runId}` returns status, timestamps, event count
**And** `GET /api/runs/{runId}/events?afterSequence=` paginates JSONL events
**And** `GET /api/strategies` lists catalog entries

### Story 13.7: Récupération après crash — 0 run perdu

As a Martin,
I want run status and journal recovered after control plane restart,
So that the MVP promise « never lose a run » holds.

**Acceptance Criteria:**

**Given** active or recently completed runs persisted in SQLite
**When** the control plane process is killed and restarted (10 trial test)
**Then** all runs remain queryable with intact event journals
**And** SM-1 metric: 0 runs lost across 10 crash/restart trials
**And** automated test documents the procedure in `trading-runtime` tests

---

## Epic 14: Onboarding stratégies

Martin peut importer SQ/JForex, enregistrer des stratégies Java et intégrer des candidats genetics au catalog.

### Story 14.1: Enregistrement stratégie Java au catalog

As a Martin,
I want hand-written Java strategies discoverable via API and CLI,
So that my custom strategies use the same pipeline as examples.

**Acceptance Criteria:**

**Given** a `Strategy` implementation wired in `trading-examples`
**When** I call `GET /api/strategies` or `RunBacktest --list`
**Then** the strategy appears with stable `strategyId`
**And** I can launch a backtest for it via `POST /api/runs` or CLI
**And** FR2 consequences are satisfied

### Story 14.2: Import SQ JForex — stratégie pilote firstSqJforx

As a Martin,
I want to import the `firstSqJforx` StrategyQuant JForex XML export,
So that I have a golden reference for the parser.

**Acceptance Criteria:**

**Given** a valid SQ/JForex XML file for `firstSqJforx`
**When** I run the import via `trading-parser`
**Then** compilable Java output is produced (full or partial)
**And** the strategy appears in StrategyCatalog
**And** a golden import test exists in CI
**And** non-JForex formats are rejected with a clear error

### Story 14.3: Rapport de conversion partielle

As a Martin,
I want an explicit gaps report when JForex constructs are unsupported,
So that I know what to fix manually.

**Acceptance Criteria:**

**Given** an XML file with unsupported JForex constructs
**When** import runs
**Then** output lists each unsupported construct with location
**And** partial Java is emitted where possible
**And** import does not fail silently

### Story 14.4: Export genetics vers catalog

As a Martin,
I want genetic search winners exported as catalog candidates,
So that research flows into the same promote pipeline.

**Acceptance Criteria:**

**Given** a genetics winner from `trading-genetics`
**When** I export to StrategyCatalog
**Then** the candidate is tagged `origin: GENETICS` in config snapshot metadata
**And** promote gates (Epic 15) apply before PAPER/LIVE

---

## Epic 15: Validation MVP & promote gates

Martin peut promouvoir une stratégie vers PAPER/LIVE uniquement si les gates passent ; export evidence pack pour audit.

### Story 15.1: Gate backtest MVP (golden + métriques minimales)

As a Martin,
I want automated backtest gates before promotion,
So that I do not promote strategies that fail baseline validation.

**Acceptance Criteria:**

**Given** a strategy with a completed BACKTEST run
**When** promote gate check runs
**Then** golden backtest contract OR configured min trades / max drawdown band is evaluated
**And** failure produces structured `GateCheckResult` with reasons
**And** FR6 MVP slice is bounded (no CPCV/WFA required)

### Story 15.2: PromoteService et DeploymentStore

As a Martin,
I want deployment records stored with checks and artifact hash,
So that every promotion is auditable.

**Acceptance Criteria:**

**Given** `PromoteService` and `DeploymentStore` implementations
**When** a promotion succeeds
**Then** `DeploymentRecord` stores `strategyId`, `mode`, `checks[]`, `artifactHash`, `promotedAt`
**And** `PromoteService` composes `RunLifecycle`, `EventGapDetector`, and stores without logic in `RunManager`

### Story 15.3: API promote avec rejet 422

As a Martin,
I want promote to fail explicitly when gates fail,
So that I understand why LIVE is blocked.

**Acceptance Criteria:**

**Given** a strategy failing one or more gates
**When** I `POST /api/strategies/{id}/promote` with `{ targetMode: PAPER|LIVE }`
**Then** HTTP 422 returns with `reasons[]` describing each failed gate
**And** successful promote returns deployment record
**And** LIVE promote blocked if paper period < 30 calendar days

### Story 15.4: Export evidence pack JSONL

As a Martin,
I want to export a complete audit bundle per run,
So that I can prove decisions externally.

**Acceptance Criteria:**

**Given** a completed run with events and config snapshot
**When** I `GET /api/runs/{runId}/export`
**Then** JSONL includes events, snapshot, metadata, operator actions (when present)
**And** export is append-only faithful (no silent overwrites)

---

## Epic 16: Exécution OANDA paper → live

Martin peut exécuter en paper OANDA demo puis LIVE sur worker local ; kill switch journalisé.

### Story 16.1: Runner PAPER_STUB labellisé

As a Martin,
I want a dev paper stub clearly labelled in events,
So that I am not misled before OANDA integration.

**Acceptance Criteria:**

**Given** PAPER mode with stub executor
**When** a run executes
**Then** events include `PAPER_STUB` label
**And** behaviour is documented as non-broker
**And** stub usable max 30 days in dev before OANDA demo required

### Story 16.2: Paper trading OANDA demo

As a Martin,
I want strategies running against OANDA demo with journaled events,
So that paper behaves like backtest observability.

**Acceptance Criteria:**

**Given** valid OANDA credentials via env (never committed)
**When** I start a PAPER run
**Then** orders route to OANDA demo from worker node
**And** ORDER/FILL events append to event store
**And** PAPER runs are distinct from BACKTEST in API and events

### Story 16.3: Gate 30 jours paper avant LIVE

As a Martin,
I want LIVE promotion blocked until 30 calendar days of paper,
So that live capital is not rushed.

**Acceptance Criteria:**

**Given** a deployment with paper started less than 30 days ago
**When** I attempt promote to LIVE
**Then** gate fails with explicit reason including elapsed days
**And** gate passes when 30 days satisfied on same deployment lineage

### Story 16.4: Exécution LIVE sur worker local

As a Martin,
I want LIVE orders executed on the worker node, not the hub,
So that the hub remains a passive observer.

**Acceptance Criteria:**

**Given** a strategy promoted to LIVE with gates passed
**When** the strategy submits MARKET orders
**Then** `trading-broker` sends orders to OANDA from worker process
**And** control plane persists events but does not route orders
**And** hub passif invariant (ADR-13-07) holds

### Story 16.5: Kill switch et OPERATOR_ACTION

As a Martin,
I want an emergency kill that stops new orders and logs the action,
So that I can halt trading with audit trail.

**Acceptance Criteria:**

**Given** an active LIVE or PAPER deployment
**When** I `POST /api/strategies/{id}/kill`
**Then** new orders are stopped
**And** an `OPERATOR_ACTION` event is appended with actor, reason, timestamp UTC
**And** action appears in evidence pack export

---

## Epic 17: Salle de contrôle

Martin voit l'état du réseau en < 3 s (Phase A) puis pause/retire/retune avec signaux drift (Phase B).

### Story 17.1: GET /control/summary — Phase A observabilité

As a Martin,
I want a single read-only endpoint showing runs, freshness and gaps,
So that I know in 10 seconds if something is wrong at 3am.

**Acceptance Criteria:**

**Given** the control plane with active runs
**When** I `GET /control/summary`
**Then** response includes `schemaVersion: 1`, `freshness`, `runs[]`, `signals.gaps[]`
**And** each run has `lastEventAt`, `isStale`, `configSnapshot`, `gaps[]`, `latestEvent`
**And** `signals.drift[]` may be empty stub in Phase A
**And** no POST actions required for this story

### Story 17.2: WebSocket dashboard patches

As a Martin,
I want live summary patches without refreshing,
So that freshness updates in real time.

**Acceptance Criteria:**

**Given** a client subscribed on `WS /ws/dashboard`
**When** a new event is appended to a run
**Then** server sends `summary` channel `patch` with updated `freshness` and affected run
**And** heartbeat messages arrive on `system` channel
**And** client refetches full summary on reconnect

### Story 17.3: Dashboard Python — vue lecture seule Phase A

As a Martin,
I want the Python dashboard to render control summary within 3 seconds,
So that FR-11 and UX-DR9 are satisfied for mono-node.

**Acceptance Criteria:**

**Given** control plane running locally
**When** I open the dashboard
**Then** runs appear sorted by severity (stale/gap first)
**And** freshness shows « Dernier event il y a X s » (UX-DR2)
**And** stale runs never show false green (UX-DR2, NFR4)
**And** no promote/kill buttons in Phase A

### Story 17.4: États lifecycle PAUSE et RETIRE

As a Martin,
I want to pause or retire a deployment with mandatory reason,
So that I control risk with audit trail.

**Acceptance Criteria:**

**Given** an ACTIVE deployment
**When** I set status to PAUSED or RETIRED with reason via API/UI
**Then** PAUSED rejects new runs for that deployment
**And** RETIRED is terminal ; historical data retained
**And** `OPERATOR_ACTION` event records transition (FR13, FR14)

### Story 17.5: Moteur signaux drift FR-15

As a Martin,
I want drift recommendations based on PRD default thresholds,
So that I know when to review, pause or retire.

**Acceptance Criteria:**

**Given** live/paper run with ≥14 days or ≥20 trades
**When** metrics deviate from promote baseline beyond configured thresholds
**Then** recommendation is HOLD, REVIEW_PARAMS, PAUSE, or RETIRE per PRD table
**And** composite rule: 2 red dimensions → PAUSE
**And** each signal logs metric, value, threshold, timestamp UTC
**And** recommendations are advisory ; operator decision always logged

### Story 17.6: Workflow retune sans hot-swap LIVE

As a Martin,
I want to fork config, backtest and promote via gates,
So that LIVE never silently changes parameters.

**Acceptance Criteria:**

**Given** a LIVE deployment pinned to `configHash`
**When** I create a forked config version and backtest it
**Then** LIVE continues on old hash until explicit promote passes gates
**And** new version appears as candidate deployment
**And** FR16 consequences satisfied

### Story 17.7: Phase B — actions UI kill, pause, drift

As a Martin,
I want kill switch and lifecycle actions in the control room with confirmation,
So that I act from one surface at 3am.

**Acceptance Criteria:**

**Given** Phase A dashboard deployed
**When** I trigger kill, pause, or retire from UI
**Then** double confirmation prevents accidental kill (UX-DR6)
**And** drift signals show HOLD/REVIEW/PAUSE/RETIRE with metrics (UX-DR7)
**And** Laravel complications MVP: exposure, daily PnL, broker connection (UX-DR5)

### Story 17.8: État réseau multi-nœud (préparation FR-11)

As a Martin,
I want node list with live > paper > backtest priority and degraded offline state,
So that distributed ops are visible before Epic 18 completes.

**Acceptance Criteria:**

**Given** zero or more registered nodes
**When** summary includes `nodes[]` with `lastHeartbeat`
**Then** offline nodes show degraded badge (UX-DR3)
**And** active run on offline node shows « état inconnu » (UX-DR4)
**And** works with mono-node (single implicit node) before Epic 18

---

## Epic 18: Plateforme distribuée

Martin peut exécuter workers sur PCs séparés avec sync idempotent et mode dégradé hub down.

### Story 18.1: Module trading-node scaffold

As a developer,
I want a `trading-node` Maven module with NodeMain entry point,
So that remote workers are isolated from hub code.

**Acceptance Criteria:**

**Given** parent POM updated
**When** `mvn test -pl trading-node -am` runs
**Then** module compiles with deps on `trading-runtime` and `trading-backtest`
**And** acyclic Maven graph preserved (ADR-13-13)

### Story 18.2: LocalOutboxStore et buffer hub down

As a Martin,
I want events buffered locally when hub is unavailable,
So that trading continues in degraded mode.

**Acceptance Criteria:**

**Given** hub unreachable
**When** worker emits events
**Then** events append to local SQLite outbox
**And** fail-closed triggers at 80% buffer capacity (ADR-13-09)
**And** alert is surfaced when threshold approached

### Story 18.3: Ingestion idempotente (runId, eventId)

As a Martin,
I want replay without duplicate events,
So that sync after partition is trustworthy.

**Acceptance Criteria:**

**Given** events replayed from worker outbox
**When** hub ingests via `POST /api/runs/{runId}/events` batch
**Then** `UNIQUE(run_id, event_id)` prevents duplicates
**And** ACK returns `{ lastAckedSequence, acceptedCount }`
**And** outbox purges only when `localSeq <= lastAckedSequence`

### Story 18.4: NodeRegistry et heartbeat

As a Martin,
I want registered nodes with capabilities and last seen,
So that the dashboard shows network state accurately.

**Acceptance Criteria:**

**Given** a worker calling `POST /api/nodes/{nodeId}/heartbeat`
**When** hub receives heartbeat
**Then** `NodeRecord` updates `lastHeartbeat`, `capabilities`, `status`
**And** `GET /api/nodes` lists nodes for dashboard (FR11, UX-DR1)

### Story 18.5: Replay complet après recovery hub

As a Martin,
I want full event replay after hub outage,
So that central journal matches local execution.

**Acceptance Criteria:**

**Given** hub was down and worker buffered events
**When** hub recovers
**Then** replay completes without unreported gaps
**And** Phase B success criterion: hub down 1h → replay OK (ADR-13)

---

## Epic 19: Validation statistique avancée

Martin peut appliquer purged WFA, CPCV et stress tests comme gates extensibles.

### Story 19.1: Interface modules de validation pluggables

As a developer,
I want a ValidationModule SPI producing GateCheckResult,
So that advanced techniques integrate without forked promote logic.

**Acceptance Criteria:**

**Given** a new validation module implementation
**When** registered in promote pipeline
**Then** results store as RunEvents with pass/fail and metadata
**And** FR6 advanced scope begins (post-MVP)

### Story 19.2: Purged walk-forward gate

As a Martin,
I want purged WFA as an optional promote gate,
So that overfit is reduced beyond golden backtest.

**Acceptance Criteria:**

**Given** historical data and strategy config
**When** purged WFA module runs
**Then** pass/fail with fold metrics stored as events
**And** documented in addendum.md technique catalog

### Story 19.3: CPCV et stress modules

As a Martin,
I want CPCV and stress test modules available,
So that world-class validation is reachable post-MVP.

**Acceptance Criteria:**

**Given** configured CPCV or stress module
**When** run as part of validation suite
**Then** gate results integrate with Epic 15 promote flow
**And** modules are optional (not MVP blocking)

---

## Epic 20: Authoring AI DeepSeek

Martin peut générer des ébauches Java via DeepSeek soumises aux mêmes gates.

### Story 20.1: Client DeepSeek via DEEPSEEK_API_KEY

As a Martin,
I want DeepSeek API access via local env only,
So that AI codegen never commits secrets.

**Acceptance Criteria:**

**Given** `DEEPSEEK_API_KEY` in env (not repo)
**When** client invokes DeepSeek API
**Then** requests succeed with configured model
**And** missing key fails with clear error
**And** NFR2 satisfied

### Story 20.2: Génération squelette Strategy Java

As a Martin,
I want AI-generated strategy hypotheses as Java skeletons,
So that ideation feeds the same backtest pipeline.

**Acceptance Criteria:**

**Given** a natural language hypothesis
**When** codegen runs
**Then** output is compilable `Strategy` skeleton with gaps marked
**And** never deploys to LIVE without Epic 15 gates

### Story 20.3: Tagging origin AI dans snapshot

As a Martin,
I want AI runs tagged in config snapshot,
So that audit distinguishes AI-authored strategies.

**Acceptance Criteria:**

**Given** a run started from AI-generated strategy
**When** `RUN_STARTED` snapshot is captured
**Then** metadata includes `origin: AI` and `llmProvider: deepseek`
**And** visible in evidence pack and API
