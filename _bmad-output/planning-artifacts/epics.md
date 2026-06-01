---

stepsCompleted: [1, 2, 3, 4]
validationStatus: prop-shop-ready
validatedAt: 2026-05-30
propShopValidation:
  frCoverage: pass
  psgrCoverage: pass
  dependencies: pass-with-note
  note: "15.8 implémentée après 16.3 (prérequis cross-epic)"
  storyCount: 57
  propShopStories: 18
  mustShip: 14
  phase2: 8
propShopEnrichment:
  startedAt: 2026-05-30
  scope: "Lacunes prop-shop §8d — enrichissement epics.md (format B)"
  priorEpicsRun: "Epics 13-20 validés 2026-05-24"
  decisions:
    psgr6_psgr7: "Répartir entre epics existants (pas Epic 21 dédié)"
    psgr9_ci: "Mini-dataset EUR_USD subset en CI"
    additions: "multi-compte, rapport HTML investisseur, IBKR"
  partyMode2026-05-30:
    - "Renumerotation Epic 16 : 16.2 = Broker skeleton avant OANDA"
    - "Must-ship ~10 stories ; Phase 2 : HTML, multi-compte, IBKR"
    - "Matrice §8d → story ; RiskEngine + ExecutionCostModel partagés"
propShopMustShip:
  - "13.8"
  - "13.9"
  - "15.5"
  - "15.6"
  - "15.8"
  - "16.2"
  - "16.3"
  - "16.4"
  - "16.5"
  - "16.6"
  - "16.7"
  - "16.8"
  - "17.9"
  - "17.10"
propShopPhase2:
  - "15.7"
  - "16.9"
  - "16.10"
  - "17.9b"
  - "17.11"
  - "17.12"
  - "19.4"
  - "19.5"
inputDocuments:

- _bmad-output/planning-artifacts/prds/prd-Trading Bridge-2026-05-24/prd.md
- _bmad-output/planning-artifacts/prds/prd-Trading Bridge-2026-05-24/addendum.md
- _bmad-output/planning-artifacts/prds/prd-Trading Bridge-2026-05-24/validation-report.md
- _bmad-output/planning-artifacts/prds/prd-Trading Bridge-2026-05-24/review-rubric.md
- _bmad-output/planning-artifacts/architecture-epic-13-platform-runtime.md
- _bmad-output/planning-artifacts/multi-machine-architecture.md
- _bmad-output/planning-artifacts/adr-13-distributed-platform.md
- _bmad-output/planning-artifacts/epics.md
- _bmad-output/project-context.md
- docs/sprint-plan.md
- _bmad-output/implementation-artifacts/12-10-backtest-engine-trust.md
- _bmad-output/implementation-artifacts/12-11-platform-test-strategies.md
- _bmad-output/brainstorming/brainstorming-session-2026-05-31.md
approvedStructure: "Epics 13-20, Epic 17 Phase A puis Phase B, ordre 13→14→15→16→17-A ; Epic 21 SQ CLI Bridge (2026-05-31)"
legacyReference:
- _bmad-output/planning-artifacts/epics-legacy-sprint-plan.md

---

# Trading Bridge - Epic Breakdown

## Overview

Ce document décompose les exigences du PRD final (2026-05-24), des ADR Epic 13 / plateforme distribuée et des spécifications dashboard (architecture) en epics et stories implémentables. Structure approuvée après party mode (2026-05-24) : epics 13–20, Epic 17 en Phase A (observabilité) puis Phase B (actions).

**Enrichissement prop-shop (2026-05-30) :** section « Prop-Shop Gap Requirements » ajoutée pour combler les lacunes §8d (broker, OOS, exécution réaliste, risk limits, CI golden). Les epics 13–20 existants restent la base ; de nouvelles stories/epics complémentaires seront designées à l'étape 2.

**Ordre de livraison recommandé (base) :** 13 → 14 → 15 → 16 → 17-A → 17-B → 18 → 19 → 20

**Priorité prop-shop (Martin, 2026-05-30) :** (1) OANDA paper/live → (2) gates promote + evidence → (3) validation OOS → (4) risk limits temps réel → (5) exécution réaliste → (6) golden CI mini-dataset → (7) multi-compte → (8) rapport HTML → (9) IBKR

**Ordre prop-shop recommandé :** 13.8 → 13.9 → 15.5 → 15.6 → **16.2** → 16.3–16.8 → **15.8** (runbook, après paper OANDA) → 17.9–17.10 → *Phase 2* : 15.7, 16.9, 16.10, 17.9b, 17.11–17.12, 19.4–19.5

**Must-ship prop-shop (solo builder, 14 stories) :** 13.8, 13.9, 15.5, 15.6, 16.2–16.8, 15.8, 17.9, 17.10 — milestone = backtest CI + paper OANDA + gates + runbook + reconciliation + risk + dashboard minimal.

**Phase 2 prop-shop (repoussé) :** 15.7 rapport HTML, 16.9 multi-compte, 16.10 IBKR, 17.11–17.12 labels/drift affinés, 19.4–19.5 OOS/stress avancés.

**Notes architecture (party mode) :**

- `ExecutionCostModel` partagé entre Epic 13 (13.9) et Epic 19 (19.5)
- `RiskEngine` composable : pre-trade (16.8) + daily DD (17.10), pas deux implémentations séparées
- `ExecutionLabel` : modèle unique Epic 15 (15.6) ; UI Epic 17 (17.11) consomme le même enum/record
- Contrat **Broker events** : ORDER_SUBMITTED, FILL, REJECT dès 16.3 — prérequis 16.7 et 17.x

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

### Prop-Shop Gap Requirements (PS-GR)

Exigences extraites de PRD §8d, addendum, validation 2026-05-30, stories 12-10/12-11 et sprint-plan — ciblent une plateforme **prop-grade** au-delà de la démo interne actuelle.

PS-GR1: **Paper OANDA réel (FR-8 gap)** — Martin exécute en PAPER via REST OANDA demo ; ordres, fills et rejets journalisés comme RunEvents ; le stub `PAPER_STUB` (replay `BacktestEngine`) reste dev-only et **ne compte pas** pour gate 30j. Realise critère §8d « paper/live broker prouvé ».

PS-GR2: **Live OANDA sur worker (FR-9 gap)** — Martin exécute LIVE ; ordres sur nœud worker via `LiveExecutor`, pas control plane ; kill switch stop nouveaux ordres ; `RunManager` accepte mode LIVE. Realise UJ-2 climax (tag Phase Epic 4).

PS-GR3: **Gates promote numériques (FR-7 gap)** — `POST /api/strategies/{id}/promote` enforce : golden backtest pass, min trades (seuil configurable), bande max drawdown, validation modules pass ; 422 avec raisons structurées si échec. Paramètres documentés dans PRD/Epic 15.

PS-GR4: **Compteur paper 30 j calendaires OANDA** — promote LIVE bloqué si paper OANDA demo < 30 jours ; stub auto-pass interdit pour gate LIVE ; compteur basé sur `DeploymentRecord` dates réelles.

PS-GR5: **Validation OOS rigoureuse (FR-6 Phase 2)** — Pipeline purged walk-forward + holdout OOS verrouillé ; purge gaps pour stratégies stateful ; résultats gates stockés RunEvents ; majority-pass + veto catastrophique (DD). Realise addendum « IS → Purged WFA → OOS holdout ».

PS-GR6: **Modèle d'exécution réaliste (§8d gap)** — Backtest/paper supporte commission, slippage configurable, rejets/latence simulés ; stress spread/slippage dégradés ; fills ne supposent pas uniquement `bar.open()` pour scénarios stress. Sprint 3 + addendum stress testing.

PS-GR7: **Risk limits temps réel (§8d gap)** — Guards daily drawdown, perte journalière max, taille position ; circuit breaker pause auto ; signaux avant violation ; intégration `RunLifecycle` + dashboard alertes. Non calculable MVP sans events broker (FR-15 tag post-Epic 4).

PS-GR8: **Réconciliation broker ↔ journal** — Positions et fills OANDA réconciliés avec EventStore ; détection ghost journal ; alerte OPERATOR_ACTION si divergence. Addendum Phase 2 live.

PS-GR9: **Confiance backtest CI (§8d ⚠️)** — `BacktestEngineContractTest` + `PlatformRobustnessTest` toujours CI ; **mini-dataset EUR_USD** (subset H1, ex. 1–3 mois) commité sous `data/ci/` pour golden E2E non conditionnel ; baseline full-precision + garde return↔PnL (story 12-10). Dataset complet reste optionnel local.

PS-GR10: **Evidence pack due diligence** — `GET /api/runs/{runId}/export` JSONL inclut config snapshot, gate results, operator actions ; pack assemblable pour revue prop-firm interne. Epic 15 S14.

PS-GR11: **Distinction stub vs broker (communication)** — Toute UI/API/rapport labelise `PAPER_STUB` vs `PAPER_OANDA` vs `LIVE_OANDA` vs `LIVE_IBKR` ; SM-2 scindée (backtest+stub vs OANDA demo). Validation PRD finding high.

PS-GR12: **Interface Broker commune** — `trading-broker` : contrat `Broker` pour MARKET/LIMIT/STOP, sync positions, reconnect ; OANDA v20 REST demo en premier, IBKR ensuite. Sprint 4 + architecture Epic 13 § LiveExecutor.

PS-GR13: **Parité BACKTEST/PAPER stub documentée** — Parité métriques stub = replay backtest (12-11) ; **non** parité avec OANDA/IBKR ; tests séparés pour paper broker. Story 12-11 AC5 LIVE guard jusqu'à Epic 4.

PS-GR14: **Drift signals post-broker (FR-15)** — Seuils drift (30j glissants, min 14j ou 20 trades) applicables uniquement quand events paper/live broker existent ; recommandations HOLD/REVIEW/PAUSE/RETIRE. Epic 17 Phase B après Epic 16.

PS-GR15: **Multi-compte prop** — Martin peut lier plusieurs comptes broker (prop firm accounts) à des deployments distincts ; credentials isolés par compte ; PnL, risk limits et gates évalués **par compte** ; dashboard agrège et filtre par compte.

PS-GR16: **Rapport HTML investisseur / due diligence** — Export HTML autonome par run ou période : equity curve, métriques (Sharpe, PF, max DD, trades), config snapshot hash, disclaimer explicite (backtest / PAPER_STUB / PAPER_OANDA / LIVE). Chart.js ou équivalent léger ; pas de SaaS.

PS-GR17: **Connecteur IBKR** — Martin peut exécuter paper/live via TWS ou IB Gateway après maturité OANDA ; même interface `Broker` ; events journalisés identiques ; promote gates identiques FR-7. Phase 2 broker post-OANDA.

**Répartition PS-GR6 / PS-GR7 (décision Martin) :**


| Exigence                               | Epic cible                     | Rationale                                    |
| -------------------------------------- | ------------------------------ | -------------------------------------------- |
| PS-GR6 commission/slippage moteur      | **Epic 13** (stories backtest) | `BacktestEngine` — fondation fills réalistes |
| PS-GR6 stress spread/slippage          | **Epic 19**                    | Gates validation stress exécution            |
| PS-GR7 daily DD / circuit breaker live | **Epic 17 Phase B**            | RunLifecycle + dashboard alertes             |
| PS-GR7 taille position / pre-trade     | **Epic 16**                    | Guards avant envoi ordre broker              |


### Prop-Shop Additional Requirements (Architecture & impl.)

- **Module `trading-broker`** — scaffold → implémentation OANDA ; `LiveExecutor` + paper broker distinct de `PaperExecutor` stub.
- **Hub passif (ADR-13)** — control plane observe ; ordres LIVE sur worker ; invariant persist→broadcast.
- **Brownfield** — étendre `trading-runtime` (`PromoteService`, `DeploymentStore`, `RunLifecycle`) sans nouveau microservice.
- **Credentials** — OANDA via env/fichiers locaux ; jamais en repo (NFR2).
- **UTC partout** — `Instant` sur events broker ; affichage Toronto UI only (NFR1).
- **RunEvent v1** — champs additive-only ; gate results et broker fills comme events typés.
- **Catalog `TestStrategies`** — harness régression sans données historiques ; 16+ scénarios edge (12-11).
- **Golden skip CI** — **Décision : mini-dataset** `data/ci/EUR_USD_H1_subset.csv` pour `GoldenBacktestTest` always-on ; contract tests complémentaires.
- **Multi-node Phase 2** — single writer broker par lease (FR-9) ; Epic 18 post prop-shop mono-nœud.

### Prop-Shop NFR Extensions

PS-NFR1: **Exécution** — Latence API control plane < 3 s pour état réseau (UX-DR9 / validation finding).
PS-NFR2: **Audit prop-grade** — Chaîne causale run → ordre broker → fill → PnL ; OPERATOR_ACTION immuable.
PS-NFR3: **Safety** — LIVE requiert gates + 30j paper OANDA ; kill switch journalisé ; pause rejette nouveaux ordres.
PS-NFR4: **Honesty** — Rapports externes distinguent backtest, paper stub, paper OANDA, live (§8d, addendum).

### Prop-Shop Gap Coverage Map

PS-GR1: Epic 16 — Paper OANDA demo réel
PS-GR2: Epic 16 — Live OANDA worker
PS-GR3: Epic 15 — Gates promote numériques
PS-GR4: Epic 15 + Epic 16 — Compteur 30j paper OANDA
PS-GR5: Epic 19 — Purged WFA + OOS holdout
PS-GR6: Epic 13 (moteur slippage/commission) + Epic 19 (stress exécution)
PS-GR7: Epic 16 (pre-trade guards) + Epic 17-B (daily DD, circuit breaker)
PS-GR8: Epic 16 — Réconciliation broker ↔ journal
PS-GR9: Epic 13 — Mini-dataset CI golden
PS-GR10: Epic 15 — Evidence pack JSONL
PS-GR11: Epic 15 + Epic 17-A — Labels stub/broker UI/API
PS-GR12: Epic 16 — Interface Broker + OANDA
PS-GR13: Epic 13 (doc/tests) — Parité stub documentée
PS-GR14: Epic 17-B — Drift post-broker
PS-GR15: Epic 16 + Epic 17-A — Multi-compte prop
PS-GR16: Epic 15 — Rapport HTML due diligence
PS-GR17: Epic 16 — Connecteur IBKR post-OANDA (**Phase 2**)

### Matrice §8d → stories prop-shop (due diligence)

| Critère §8d | Statut cible | Stories | Test / preuve |
|-------------|---------------|---------|---------------|
| Backtest reproductible | ✅ fermé | 13.3–13.5, **13.8**, 13.9 | `GoldenBacktestTest`, `BacktestEngineContractTest`, config hash |
| Audit trail | ✅ fermé | 13.3, 15.2, 15.4, 16.6 | JSONL export, `DeploymentRecord` |
| Gates research → production | ⚠️ → ✅ | **15.5**, 15.6, 16.4 | `PromoteServiceTest`, 422 reasons |
| Modèle exécution réaliste | ❌ → ⚠️ | **13.9**, 19.5 (Phase 2) | Commission/slippage AC numériques |
| Walk-forward / OOS | ❌ → ⚠️ | 19.2, **19.4** (Phase 2) | Holdout RunEvents |
| Risk limits temps réel | ❌ → ⚠️ | **16.8**, **17.10** | `RiskEngineTest`, ORDER_REJECTED |
| Paper / live broker | ❌ → ⚠️ | **16.2–16.5**, 16.7 | OANDA integration gated ; stub exclu gate LIVE |
| CI non-régression | ⚠️ → ✅ | **13.8**, 12-10, 12-11 | Mini-dataset always-on CI |
| Runbook opérationnel | ⚠️ nouveau | **15.8** | Checklist documentée + API status |
| SM-2 stub vs broker | ⚠️ → ✅ | **15.6**, 16.1 | `executionLabel` ; stub 422 vers LIVE |

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
FR-SQ1: Epic 21 — Hot folder ingest automatique (21.1–21.3)
FR-SQ2: Epic 21 — Pilotage sqcli Mac (21.4–21.5)
FR-SQ3: Epic 21 — Pipeline nightly (21.6)
FR-SQ4: Epic 21 — Fitness CSV → SQ ext indicators (21.8)

## Epic List

### Epic 13: Fondation runtime & journal durable

Martin peut lancer des backtests reproductibles via le control plane ; les runs et events survivent aux crashs avec config immuable et gaps signalés.
**FRs covered:** FR4, FR5, FR10 | **PS-GR:** PS-GR6 (moteur), PS-GR9, PS-GR13 | **NFRs:** NFR3, NFR4, NFR10 | **Sprint:** S13

### Epic 14: Onboarding stratégies

Martin peut importer SQ/JForex, enregistrer des stratégies Java et intégrer des candidats genetics au catalog.
**FRs covered:** FR1, FR2, FR17 | **Sprint:** S13–14

### Epic 15: Validation MVP & promote gates

Martin peut promouvoir une stratégie vers PAPER/LIVE uniquement si les gates passent ; export evidence pack JSONL pour audit ; rapport HTML en **Phase 2**.
**FRs covered:** FR6 (MVP), FR7, FR14 (export) | **PS-GR:** PS-GR3, PS-GR4, PS-GR10, PS-GR11, PS-GR16 (Phase 2) | **Must-ship:** 15.5, 15.6, 15.8 | **Sprint:** S14

### Epic 16: Exécution broker (OANDA must-ship → IBKR Phase 2)

Martin peut exécuter en paper OANDA demo puis LIVE sur worker local ; kill switch journalisé. **Multi-compte et IBKR en Phase 2** après un compte OANDA stable.
**FRs covered:** FR8, FR9 | **PS-GR:** PS-GR1, PS-GR2, PS-GR7, PS-GR8, PS-GR12 | **Must-ship:** 16.2–16.8 | **Phase 2:** 16.9, 16.10 | **Impl order:** 16.1 → **16.2** → 16.3 → 16.4 → 16.5 → 16.6 → 16.7 → 16.8

### Epic 17: Salle de contrôle

Martin voit l'état du réseau en < 3 s (Phase A) puis pause/retire/retune avec **risk limits live** et signaux drift (Phase B). Vue multi-compte en **Phase 2**.
**FRs covered:** FR11, FR13, FR14, FR15, FR16 | **PS-GR:** PS-GR7, PS-GR11, PS-GR14 | **Must-ship:** 17.9, 17.10 | **Phase 2:** 17.11, 17.12, 17.9 multi-compte filter

### Epic 18: Plateforme distribuée

Martin peut exécuter workers sur PCs séparés avec sync idempotent et mode dégradé hub down.
**FRs covered:** FR12 | **Sprint:** S15–16

### Epic 19: Validation statistique avancée

Martin peut appliquer purged WFA, CPCV, **stress tests exécution** (spread/slippage dégradés) comme gates extensibles.
**FRs covered:** FR6 (reste) | **PS-GR:** PS-GR5, PS-GR6 (stress) | **Phase:** 2

### Epic 20: Authoring AI DeepSeek

Martin peut générer des ébauches Java via DeepSeek soumises aux mêmes gates.
**FRs covered:** FR3 | **Phase:** 3

### Epic 21: Pont StrategyQuant CLI (pipeline automatisé)

Martin peut faire communiquer StrategyQuant X et Trading Bridge sur Mac : hot folder XML, pilotage sqcli, pipeline nightly et boucle fitness TB→SQ via indicateurs externes.
**FRs covered:** FR-SQ1–FR-SQ4, FR1 (automatisé) | **Prérequis:** Epic 2 (parser 2-1…2-8), Epic 12/13 (RunBacktest, runtime optionnel) | **Sprint:** post Epic 2 codegen (2-9) ou parallèle inbox | **Source:** brainstorming 2026-05-31

### Synthèse prop-shop (enrichissement Epic 13–20)


| Epic   | Must-ship | Phase 2 |
| ------ | --------- | ------- |
| 13     | 13.8, 13.9 | — |
| 15     | 15.5, 15.6, 15.8 runbook | 15.7 HTML |
| 16     | 16.2–16.8 (ordre : 16.2 skeleton avant 16.3 OANDA) | 16.9 multi-compte, 16.10 IBKR |
| 17     | 17.9, 17.10 | 17.11 labels UI, 17.12 drift post-broker |
| 19     | — | 19.4 OOS holdout, 19.5 stress |


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

### Story 13.8: Mini-dataset CI pour golden E2E (prop-shop) — **Must-ship**

As a Martin,
I want a committed EUR_USD H1 subset so golden backtest runs in CI without local data,
So that prop-shop backtest trust is proven on every build (PS-GR9).

**Acceptance Criteria:**

**Given** `data/ci/EUR_USD_H1_subset.csv` committed (1–3 months H1 bars, documented bar count)
**When** `GoldenBacktestTest` runs in CI without `data/historical/`
**Then** the test executes against the mini-dataset and asserts baseline metrics within tolerance
**And** full dataset path remains optional for local extended validation
**And** `BacktestEngineContractTest` + `PlatformRobustnessTest` still run on every CI build
**And** `docs/testing.md` documents mini-dataset provenance and expected metrics

### Story 13.9: Commission et slippage configurables BacktestEngine (prop-shop) — **Must-ship**

As a Martin,
I want configurable commission and slippage in backtests,
So that promotion gates reflect realistic execution costs before live capital (PS-GR6).

**Acceptance Criteria:**

**Given** a backtest run with `commissionPerTrade` and `slippagePct` (or equivalent) in run config
**When** `BacktestEngine` processes MARKET and STOP/LIMIT fills
**Then** costs reduce PnL and appear in run metrics/events
**And** default config preserves current behaviour (zero commission, existing fill semantics)
**And** unit tests cover at least one scenario with non-zero commission and slippage
**And** config snapshot at RUN_STARTED captures cost parameters
**And** (test: `BacktestEngineContractTest` — non-zero slippage/commission scenario ; shared `ExecutionCostModel` with Epic 19.5)

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

Martin peut promouvoir une stratégie vers PAPER/LIVE uniquement si les gates passent ; export evidence pack JSONL pour audit ; rapport HTML en **Phase 2**.

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

### Story 15.5: Seuils gates promote numériques (prop-shop) — **Must-ship**

As a Martin,
I want documented numeric thresholds for promote gates,
So that prop-shop decisions are reproducible and auditable (PS-GR3, PS-GR4).

**Prerequisites:** Story 13.8 recommended ; Story 13.9 optional.

**Acceptance Criteria:**

**Given** a configuration file or API defaults for gate thresholds
**When** `PromoteService` evaluates promote to PAPER or LIVE
**Then** enforced checks include at minimum: golden/mini-golden pass, `minTrades`, `maxDrawdownPct` band, validation module pass (when enabled)
**And** LIVE promote requires ≥30 calendar days on **PAPER_OANDA** deployment (stub excluded per PS-GR4)
**And** failed checks return structured reasons with threshold, actual value, and gate name
**And** thresholds are documented in `docs/testing.md` or PRD addendum
**And** (test: `PromoteServiceTest` — gate failure returns reasons with numeric fields)

### Story 15.6: Modèle ExecutionLabel et contrat SM-2 (prop-shop) — **Must-ship**

As a Martin,
I want a single execution label model on deployments and promote responses,
So that PAPER_STUB is never confused with real broker paper (PS-GR11, SM-2).

**Prerequisites:** None — defines shared model for Epic 16–17.

**Acceptance Criteria:**

**Given** deployments in BACKTEST, PAPER_STUB, PAPER_OANDA, LIVE_OANDA, or LIVE_IBKR modes
**When** I query `GET /api/strategies/{id}/deployments` or attempt promote
**Then** each record exposes canonical `executionLabel` enum (single source in `trading-runtime`)
**And** promote to LIVE from PAPER_STUB returns 422 with reason « stub does not count toward paper period »
**And** evidence pack JSONL includes `executionLabel` in snapshot metadata
**And** (test: `PromoteServiceTest`, `ControlPlaneServerTest`)

### Story 15.7: Rapport HTML due diligence (prop-shop) — **Phase 2**

As a Martin,
I want a self-contained HTML report per run or period,
So that I can share external due diligence when submission-ready (PS-GR16).

**Prerequisites:** Stories 15.4, 15.6 ; optional 16.7.

**Acceptance Criteria:**

**Given** a completed run with metrics, trades, config snapshot hash, and execution label
**When** I request HTML export (e.g. `GET /api/runs/{runId}/export?format=html`)
**Then** the report includes equity summary, trade table, Sharpe/PF/max DD where computable, and config hash
**And** a prominent disclaimer states execution mode (backtest / stub / OANDA / LIVE)
**And** report renders offline without requiring the control plane
**And** JSONL export (Story 15.4) remains available

### Story 15.8: Runbook opérationnel paper → promote (prop-shop) — **Must-ship**

As a Martin,
I want a documented operational ritual for the 30-day paper observation period,
So that promotion is a deliberate decision, not an accidental click.

**Prerequisites:** Stories 15.5, 15.6, 16.3.

**Acceptance Criteria:**

**Given** a strategy on PAPER_OANDA
**When** I follow `docs/prop-shop-runbook.md`
**Then** checklist covers daily review, gate status, reconciliation alerts, promote/kill decision
**And** API exposes promote-readiness (elapsed paper days, gates, reconciliation status)
**And** runbook states PAPER_STUB is dev-only and excluded from LIVE path
**And** (test: API test for readiness endpoint structure)

---

## Epic 16: Exécution broker (OANDA must-ship → IBKR Phase 2)

Martin peut exécuter en paper OANDA demo puis LIVE sur worker local ; kill switch journalisé. **Ordre d'implémentation : 16.1 → 16.2 → 16.3 → …** Multi-compte et IBKR en **Phase 2**.

### Story 16.1: Runner PAPER_STUB labellisé

As a Martin,
I want a dev paper stub clearly labelled in events,
So that I am not misled before OANDA integration.

**Acceptance Criteria:**

**Given** PAPER mode with stub executor
**When** a run executes
**Then** events include `PAPER_STUB` / `executionLabel` from Story 15.6
**And** behaviour is documented as non-broker
**And** stub never satisfies LIVE promote gate (Story 15.6)
**And** (test: `PaperExecutorTest`, `PlatformRobustnessTest`)

### Story 16.2: Interface Broker skeleton (prop-shop) — **Must-ship**

As a developer,
I want a minimal shared `Broker` interface before OANDA integration,
So that execution code does not hard-code OANDA types (PS-GR12 ; party mode reorder).

**Prerequisites:** Story 16.1 (PAPER_STUB harness).

**Module cible:** `trading-broker` (interface + fake) ; HTTP client OANDA reste dans `trading-data`.

**Acceptance Criteria:**

**Given** the `trading-broker` module scaffold
**When** `Broker` exposes `submitOrder`, `getPositions`, `getAccountState`, reconnect hook
**Then** `FakeBroker` in tests satisfies the contract
**And** `BrokerEvent` types cover ORDER_SUBMITTED, FILL, REJECT (contrat events pour 16.7+)
**And** `trading-runtime` depends on `Broker` interface only, not OANDA types
**And** credentials load from env/local config only (NFR2)
**And** (test: `trading-broker/src/test/.../FakeBrokerTest`)

### Story 16.3: Paper trading OANDA demo — **Must-ship**

As a Martin,
I want strategies running against OANDA demo with journaled events,
So that paper proves broker execution before LIVE (PS-GR1).

**Prerequisites:** **Story 16.2** (Broker interface) ; Story 15.6 (ExecutionLabel).

**Module cible:** `OandaBroker` in `trading-broker` ; REST client in `trading-data`.

**Acceptance Criteria:**

**Given** valid OANDA credentials via env (never committed)
**When** I start a PAPER run with `executionLabel: PAPER_OANDA`
**Then** orders route via `Broker` to OANDA demo from worker node
**And** ORDER_SUBMITTED / FILL / REJECT events append to event store
**And** PAPER_OANDA runs are distinct from BACKTEST and PAPER_STUB in API
**And** (test: integration test with mock HTTP or gated `@Tag("oanda")` test)

### Story 16.4: Gate 30 jours paper avant LIVE — **Must-ship**

As a Martin,
I want LIVE promotion blocked until 30 calendar days of **PAPER_OANDA**,
So that live capital is not rushed (PS-GR4).

**Prerequisites:** Stories 15.5, 15.6, 16.3.

**Acceptance Criteria:**

**Given** a PAPER_OANDA deployment started less than 30 days ago
**When** I attempt promote to LIVE
**Then** gate fails with explicit reason including elapsed days
**And** PAPER_STUB duration does not count
**And** gate passes when 30 days satisfied on same deployment lineage
**And** (test: `PromoteServiceTest`)

### Story 16.5: Exécution LIVE sur worker local — **Must-ship**

As a Martin,
I want LIVE orders executed on the worker node, not the hub,
So that the hub remains a passive observer (PS-GR2).

**Prerequisites:** Stories 16.2, 16.3, 16.4.

**Acceptance Criteria:**

**Given** a strategy promoted to LIVE with gates passed
**When** the strategy submits MARKET orders
**Then** `OandaBroker` sends orders from worker process via `Broker` interface
**And** control plane persists events but does not route orders (ADR-13-07)
**And** (test: `RunManagerTest` or integration with FakeBroker)

### Story 16.6: Kill switch et OPERATOR_ACTION — **Must-ship**

As a Martin,
I want an emergency kill that stops new orders and logs the action,
So that I can halt trading with audit trail.

**Prerequisites:** Story 16.3 or 16.5 (active broker run).

**Acceptance Criteria:**

**Given** an active LIVE or PAPER_OANDA deployment
**When** I `POST /api/strategies/{id}/kill`
**Then** new orders are stopped
**And** an `OPERATOR_ACTION` event is appended with actor, reason, timestamp UTC
**And** action appears in evidence pack export
**And** (test: `ControlPlaneServerTest`)

### Story 16.7: Réconciliation broker ↔ journal (prop-shop) — **Must-ship**

As a Martin,
I want broker positions reconciled against the event journal,
So that ghost fills are detected before prop-shop review (PS-GR8).

**Prerequisites:** Story 16.3 (broker events contract).

**Acceptance Criteria:**

**Given** an active PAPER_OANDA or LIVE run
**When** periodic reconciliation runs
**Then** broker positions are compared to journal-derived state
**And** divergence emits `RECONCILIATION_ALERT` RunEvent
**And** skipped for PAPER_STUB
**And** (test: `ReconciliationServiceTest` with FakeBroker)

### Story 16.8: Pre-trade risk guards via RiskEngine (prop-shop) — **Must-ship**

As a Martin,
I want orders blocked before submission when risk limits would be breached,
So that prop-shop rules are enforced at execution time (PS-GR7).

**Prerequisites:** Story 16.2 ; shares `RiskEngine` with Story 17.10.

**Acceptance Criteria:**

**Given** configured limits: max position size, max open exposure
**When** a strategy submits an order on PAPER_OANDA or LIVE
**Then** `RiskEngine.checkPreTrade()` runs before `Broker.submit`
**And** rejected orders emit `ORDER_REJECTED` with limit breached
**And** PAPER_STUB skips broker pre-trade (documented)
**And** (test: `RiskEngineTest`)

### Story 16.9: Multi-compte prop — BrokerAccount (prop-shop) — **Phase 2**

As a Martin,
I want separate broker accounts linked to isolated deployments,
So that multiple prop firm accounts do not share PnL or risk limits (PS-GR15).

**Prerequisites:** Stories 16.3–16.8 stable on **single account** first.

**Acceptance Criteria:**

**Given** multiple account credentials configured locally
**When** I create deployments with distinct `brokerAccountId`
**Then** each deployment uses only its credentials and tags events
**And** promote gates and PnL evaluate per account
**And** API masks secrets ; cross-account routing blocked
**And** (test: `DeploymentStoreTest` multi-account)

### Story 16.10: Connecteur IBKR paper/live (prop-shop) — **Phase 2**

As a Martin,
I want IBKR execution via TWS or IB Gateway after OANDA is stable,
So that I am not locked to one broker (PS-GR17).

**Prerequisites:** Stories 16.2–16.8 on OANDA ; Story 16.9 optional.

**Acceptance Criteria:**

**Given** IBKR TWS or Gateway running locally
**When** I start PAPER_IBKR or LIVE_IBKR
**Then** `IbkrBroker` implements shared `Broker` interface
**And** events journal identically to OANDA
**And** promote gates unchanged including 30-day paper rule
**And** OANDA tests still pass independently

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

### Story 17.9: Dashboard prop-shop minimal (prop-shop) — **Must-ship**

As a Martin,
I want a control room view showing broker runs, freshness, and execution labels,
So that I supervise paper/live without deep navigation (PS-GR11 ; milestone prop-shop).

**Prerequisites:** Stories 16.3, 15.6 (ExecutionLabel in API).

**Acceptance Criteria:**

**Given** active PAPER_OANDA or LIVE runs
**When** I open dashboard or `GET /control/summary`
**Then** runs show `executionLabel`, freshness, stale/gap severity sort
**And** PAPER_STUB visually distinct from PAPER_OANDA
**And** (test: dashboard smoke or API contract test)
**And** multi-compte filter deferred to Phase 2 (Story 17.9b below)

### Story 17.9b: Vue multi-compte dashboard (prop-shop) — **Phase 2**

As a Martin,
I want to filter and aggregate by broker account,
So that I supervise multiple prop accounts from one surface (PS-GR15).

**Prerequisites:** Story 16.9 ; Story 17.9.

**Acceptance Criteria:**

**Given** multiple deployments with distinct `brokerAccountId`
**When** I filter `GET /control/summary?brokerAccountId=`
**Then** runs, PnL, freshness filter by account
**And** account selector never displays raw credentials

### Story 17.10: Daily drawdown guard via RiskEngine (prop-shop) — **Must-ship**

As a Martin,
I want automatic pause when daily drawdown exceeds configured threshold,
So that prop-shop loss limits trigger without manual intervention (PS-GR7).

**Prerequisites:** Story 16.8 (`RiskEngine` shared) ; Story 16.3 (broker PnL events).

**Acceptance Criteria:**

**Given** a LIVE or PAPER_OANDA deployment with `maxDailyDrawdownPct` configured
**When** rolling daily PnL breaches the threshold
**Then** `RiskEngine.checkDailyDrawdown()` triggers PAUSED with reason `DAILY_DD_BREACH`
**And** `OPERATOR_ACTION` event emitted ; new orders blocked until resume
**And** metrics appear in dashboard (UX-DR7)
**And** (test: `RiskEngineTest` daily DD scenario)

### Story 17.11: Labels mode exécution dans summary UI (prop-shop) — **Phase 2**

As a Martin,
I want enhanced execution label styling in the control room,
So that stub replay is never mistaken for broker execution at a glance (PS-GR11 UI polish).

**Prerequisites:** Stories 15.6, 17.9 (labels already in API).

**Acceptance Criteria:**

**Given** runs in all execution modes
**When** summary or run detail renders in Laravel/Python dashboard
**Then** `executionLabel` badge uses distinct colors per mode
**And** matches API/evidence pack metadata exactly

### Story 17.12: Drift actif post-broker uniquement (prop-shop) — **Phase 2**

As a Martin,
I want drift signals computed only when broker execution events exist,
So that FR-15 does not fire misleading alerts on stub runs (PS-GR14).

**Prerequisites:** Stories 17.5, 16.3.

**Acceptance Criteria:**

**Given** only BACKTEST or PAPER_STUB history
**When** drift engine evaluates
**Then** no broker drift recommendation (HOLD / insufficient data)
**Given** ≥14 days or ≥20 trades on PAPER_OANDA or LIVE
**When** metrics deviate from promote baseline
**Then** Story 17.5 recommendations apply with `dataSource: BROKER`
**And** (test: drift engine unit test with/without broker events)

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

Martin peut appliquer purged WFA, CPCV, **stress tests exécution** (spread/slippage dégradés) comme gates extensibles.

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

### Story 19.4: Holdout OOS verrouillé (prop-shop) — **Phase 2**

As a Martin,
I want a final OOS holdout never used during parameter search,
So that prop-shop validation avoids leakage beyond purged WFA (PS-GR5).

**Acceptance Criteria:**

**Given** historical data split into IS, WFA folds, and locked OOS holdout
**When** purged WFA module runs with parameters selected on IS/WFA only
**Then** final OOS holdout is evaluated once with frozen parameters
**And** OOS pass/fail stored as RunEvent with fold metrics and holdout period
**And** holdout date range is immutable in config snapshot for the validation run
**And** failure blocks promote when holdout gate is enabled

### Story 19.5: Stress test exécution spread/slippage dégradés (prop-shop) — **Phase 2**

As a Martin,
I want stress gates with degraded spread and slippage,
So that strategies fail promote before live if fragile to execution costs (PS-GR6 stress).

**Acceptance Criteria:**

**Given** a validation profile with elevated `slippagePct` and/or spread multiplier
**When** stress execution module runs backtest with Story 13.9 cost model
**Then** pass/fail compares metrics against baseline thresholds (e.g. max DD veto)
**And** results integrate with Epic 15 promote as optional gate
**And** at least one deterministic stress scenario exists in CI (mini-dataset compatible)
**And** uses shared `ExecutionCostModel` from Story 13.9
**And** integrates with Epic 15 promote as optional gate

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

---

## Epic 21: Pont StrategyQuant CLI (pipeline automatisé)

Martin peut orchestrer StrategyQuant X depuis Trading Bridge sur **Mac** : déposer des exports XML, lancer sqcli de façon fiable, exécuter un pipeline nightly, et renvoyer des scores fitness à SQ via indicateurs externes — sans agent Windows ni parsing `.sqx`.

**Prérequis :** Epic 2 stories 2-1…2-8 ; `RunBacktest` / `StrategyCatalog` (Epic 12) ; runtime (Epic 13) optionnel pour 21.7.

**Module cible :** `trading-parser` (`com.martinfou.trading.parser.sq.bridge.*`) ; scripts `scripts/sq/` ; data `data/sq-inbox/`.

**Ordre recommandé :** 21.1 → 21.2 → 21.3 → 21.4 → 21.5 → 21.6 → 21.7 → 21.8

### Story 21.1: Hot folder et manifest stratégie

As a Martin,
I want a standard inbox folder layout and JSON manifest schema for SQ XML drops,
So that every export is traceable before parse or backtest.

**Acceptance Criteria:**

**Given** the repo root
**When** I run setup or first inbox use
**Then** directories exist: `data/sq-inbox/{pending,passed,failed,dlq}`
**And** dropping an XML into `pending/` expects an optional sidecar `*.manifest.json` with fields: `id`, `symbol`, `timeframe`, `sqBuild`, `contentSha256`, `exportedAt` (ISO-8601 UTC)
**And** if manifest is absent, processor generates one from XML probe (`SqXmlFormatProbe`) before move
**And** `docs/contributing.md` documents Mac paths and « no spaces in sqcli paths » workaround (symlink staging)
**And** `.gitignore` excludes inbox contents except `.gitkeep`

### Story 21.2: SqInboxProcessor — ingest automatique XML

As a Martin,
I want a CLI that processes pending SQ XML through parse and backtest,
So that I get pass/fail classification without manual Maven steps.

**Acceptance Criteria:**

**Given** one or more XML files in `data/sq-inbox/pending/`
**When** I run `SqInboxProcessor` via `mvn exec:java -pl trading-parser` (or documented script)
**Then** each file is parsed with `SqXmlParser` into `StrategyConfig`
**And** a backtest runs via shared `RunBacktest` helper or equivalent with configurable symbol/bars/capital (defaults documented)
**And** on success the XML (+ manifest) moves to `passed/` and a summary JSON is written alongside
**And** on parse/backtest failure files move to `failed/` or `dlq/` per story 21.3 rules
**And** unit/integration test uses at least one existing `sqimported` XML fixture
**And** works offline without control plane running (FR-SQ1)

### Story 21.3: Validation XML, DLQ et couverture parser

As a Martin,
I want invalid or partially unsupported SQ exports rejected with explicit reasons,
So that the inbox never silently ingests garbage.

**Acceptance Criteria:**

**Given** an XML file in `pending/`
**When** `SqInboxProcessor` runs validation before expensive backtest
**Then** `XmlValidator` checks well-formed XML and minimum StrategyFile structure
**And** `ParseCoverageReport` lists supported vs unsupported building blocks with percentages
**And** files below configurable coverage threshold (default documented) go to `dlq/` with `reason.json`
**And** malformed XML never reaches backtest
**And** tests cover: valid fixture → passed path; fixture with known unsupported block → dlq with reason

### Story 21.4: SqCliRunner — wrapper sqcli Mac natif

As a Martin,
I want Trading Bridge to invoke StrategyQuant CLI reliably on Mac,
So that sqcli becomes a programmable pipeline step.

**Acceptance Criteria:**

**Given** `SQ_HOME` env var or `sq.home` property pointing to StrategyQuant install (Mac)
**When** `SqCliRunner.run(List<String> args)` or `runScript(Path commandsFile)` is called
**Then** ProcessBuilder executes `$SQ_HOME/sqcli` with captured stdout/stderr
**And** exit code, duration, and command line are logged via SLF4J
**And** missing `SQ_HOME` fails fast with clear error
**And** integration test skipped in CI when `SQ_HOME` unset ; runnable locally when set
**And** FR-SQ2 satisfied

### Story 21.5: Mutex jobs sqcli et registre de scripts

As a Martin,
I want only one sqcli job at a time and versioned command scripts,
So that concurrent runs never corrupt SQ databanks.

**Acceptance Criteria:**

**Given** multiple callers could trigger sqcli
**When** `SqJobMutex` acquires a file lock under `data/sq-inbox/.sqcli.lock`
**Then** second caller blocks or fails with « sqcli busy » per config
**And** lock releases on process exit or timeout (documented)
**And** `scripts/sq/` contains at least `commands-update-data.txt` and `commands-list-databanks.txt` with documented sqcli lines
**And** `SqCliRunner.runScript("update-data")` resolves script by name from that directory
**And** unit test verifies mutex exclusion with mock/long-running stub

### Story 21.6: Pipeline nightly sqcli → inbox

As a Martin,
I want one command to run sqcli maintenance then drain the inbox,
So that SQ generation and TB validation chain nightly without babysitting.

**Acceptance Criteria:**

**Given** `SQ_HOME` configured and sqcli scripts present
**When** I run documented entry point (e.g. `scripts/sq-nightly.sh` or Maven profile `sq-nightly`)
**Then** sqcli runs `update-data` then `list-databanks` scripts under mutex
**And** optional export step copies new XML from configured SQ export path into `data/sq-inbox/pending/` (env `SQ_EXPORT_DIR`)
**And** `SqInboxProcessor` runs automatically after export
**And** summary report printed: sqcli exit codes, files processed, passed/failed/dlq counts
**And** `caffeinate -i` documented for long Mac runs (optional wrapper flag)
**And** FR-SQ3 satisfied

### Story 21.7: Hooks runtime — santé sqcli et job optionnel

As a Martin,
I want the control plane to expose SQ bridge status and optional job triggers,
So that the TUI/dashboard shows whether SQ integration is alive.

**Acceptance Criteria:**

**Given** control plane running (Epic 13)
**When** `GET /health` or `GET /api/sq-bridge/status` is called
**Then** response includes `sqHomeConfigured`, `sqcliReachable` (last probe result), `inboxPendingCount`
**And** optional `POST /api/sq-bridge/process-inbox` enqueues inbox drain (async, respects mutex)
**And** optional `RunEvent` type `SQ_EXPORT_RECEIVED` when inbox file processed
**And** TUI `/sq` or `/inbox` command lists pending count (extends 13.6)
**And** all endpoints no-op gracefully when SQ not configured

### Story 21.8: Boucle fitness TB→SQ via indicateurs externes

As a Martin,
I want backtest scores exported as SQ external indicator CSV and re-imported via sqcli,
So that StrategyQuant Retester can filter candidates using TB validation.

**Acceptance Criteria:**

**Given** completed inbox/backtest results with metrics (Sharpe, PF, max DD, composite score)
**When** `--sq-feedback` flag or config enabled on batch/inbox run
**Then** TB writes CSV matching SQ ext-indicator format (date `dd/MM/yyyy`, time `HH:mm:ss` per SQ CLI doc)
**And** `SqCliRunner` runs `-extindicators action=import name=tbFitness file=…` under mutex
**And** indicator `tbFitness` created once via documented `action=add` script if missing
**And** end-to-end test skipped in CI without SQ_HOME ; manual checklist in dev notes
**And** FR-SQ4 satisfied

**Notes Epic 21 :**

- Ne pas parser `.sqx` — rejeter avec message clair.
- Epic 14 = onboarding manuel ; Epic 21 = flux automatisé SQ↔TB.
- Story 2-9 (codegen) indépendante ; inbox évalue via interpréteur parser.
- Dedup hash (brainstorm #67) : optionnel post-21.2 si retraitement bruyant.