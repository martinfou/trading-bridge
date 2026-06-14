---

## title: Trading Bridge — Plateforme E2E de trading algorithmique

status: final
created: 2026-05-24
updated: 2026-06-13
owner: Martin Fournier
epic: platform-e2e
revision: "2026-06-13 — exception HARNESS sur les promote gates PAPER"
related:

- _bmad-output/planning-artifacts/adr-13-distributed-platform.md
- _bmad-output/planning-artifacts/architecture-epic-13-platform-runtime.md
- _bmad-output/planning-artifacts/prds/prd-Trading Bridge-2026-05-24/addendum.md

# PRD : Trading Bridge — Plateforme E2E

## 0. Document Purpose

Ce PRD s'adresse à **Martin Fournier** (product owner / quant / opérateur) et aux workflows downstream (architecture, epics/stories, UX dashboard, implémentation).

Il décrit une **plateforme de trading algorithmique de bout en bout** : conception de stratégies, validation, paper trading, production live, monitoring et gouvernance du cycle de vie — avec **historique immuable** de tous les changements.

**Structure :** glossaire ancré, features groupées avec FR numérotés, assumptions taguées `[ASSUMPTION]`, détails techniques de validation avancée dans `addendum.md`.

**Inputs existants :** ADR-13 distribué, Epic 13 runtime, brainstorming 2026-05-24, codebase Maven (`trading-core`, `trading-backtest`, `trading-runtime`, `trading-genetics`, `trading-parser` scaffold).

---

## 1. Vision

Trading Bridge est la **chaîne unique** entre une idée de stratégie (importée depuis StrategyQuant, écrite en Java, ou générée par AI) et son exécution réelle sur le marché — en commençant par le **forex** via OANDA.

Aujourd'hui, le flux est pénible : trouver des stratégies payantes, les mettre en paper, vérifier qu'elles performent encore, passer en production, les monitorer — le tout avec des outils éclatés et peu de guidance sur **quand ajuster**, **quand mettre en pause**, ou **quand retirer** une stratégie.

La plateforme doit répondre : *« Cette stratégie est-elle encore digne de confiance ? Que faire maintenant ? Et puis-je prouver chaque décision que j'ai prise ? »*

À terme, l'ambition est **world class** en validation statistique (au-delà du simple backtest et du walk-forward naïf). Le MVP se concentre sur un pipeline fiable backtest → paper → live avec **journal d'événements durable** et **gates de promotion** ; les techniques avancées (CPCV, purged WFA, stress testing) sont planifiées post-MVP (voir addendum).

---

## 2. Target User

### 2.1 Primary Persona

**Martin — quant-opérateur solo.** Développe et opère ses propres stratégies forex. En journée ou la nuit, il enchaîne recherche, tests, paper et live. Il achète parfois des stratégies externes et doit les valider rigoureusement avant de risquer du capital. Il veut une **seule console** et une **trace auditable** — pas dix terminaux et des spreadsheets.

### 2.2 Jobs To Be Done

- **Importer ou créer** une stratégie sans réécrire tout le stack à chaque fois
- **Prouver** qu'une stratégie tient avant paper/live (backtest reproductible, golden tests)
- **Promouvoir** une stratégie de backtest → paper → live avec des règles claires
- **Surveiller** l'état des runs et des déploiements (live en tête de file)
- **Décider** quand retuner, pauser ou retirer — avec recommandations basées sur des signaux mesurables
- **Retrouver** l'historique complet : config, paramètres, promotions, interventions, perf

### 2.3 Non-Users (v1)

- Équipes multi-utilisateurs avec rôles RBAC
- Clients externes / SaaS public
- Traders actions/options/crypto (v2+)
- Compliance tiers réglementés exigeant WORM 7 ans (v2+)

### 2.4 Key User Journeys

**UJ-1. Martin importe une stratégie StrategyQuant et la valide en backtest.**

Martin exporte une stratégie JForex depuis StrategyQuant. Il l'importe dans Trading Bridge via le parser XML. Il lance un backtest EUR_USD 2012 depuis le control plane (sans Maven manuel). Il consulte les métriques et le flux d'événements. Le run est persisté avec snapshot config immuable. **Climax :** il voit RUN_ENDED + métriques et sait que le run survivra à un redémarrage. **Edge case :** parser incomplet → message clair + rapport de conversion partielle.

**UJ-2. Martin promeut une stratégie gagnante en paper puis live.**

Après backtest satisfaisant (gate vert), Martin déclenche promote → PAPER sur OANDA demo. Il surveille le dashboard : positions, PnL, fraîcheur des events. Gate paper OK → promote → LIVE sur un compte restreint. **Climax :** ordres partent du nœud local ; le hub journalise sans router les ordres. **Edge case :** hub down → nœud continue, buffer local, replay à reconnexion.

**UJ-3. Martin détecte une dérive et met une stratégie en pause.**

Le dashboard signale : perf live sous le seuil vs backtest récent, ou drawdown > limite. Martin ouvre l'historique : config au lancement, changements de paramètres, events ordre/fill. Il choisit **Pause** avec motif. L'action est journalisée (`OPERATOR_ACTION`). **Climax :** statut PAUSED visible partout ; pas d'ordre nouveau ; historique intact.

**UJ-4. Martin explore une idée AI et la soumet au même pipeline.**

Martin décrit une hypothèse (session London breakout + filtre ATR). DeepSeek génère une ébauche Java `Strategy`. Même chemin : backtest → gates → paper. **Climax :** aucun raccourci vers live sans gates. (Phase 3)

---

## 3. Glossary

- **Strategy** — Artefact exécutable implémentant l'interface moteur ; identifié par `strategyId` dans le catalog.
- **Run** — Exécution unique d'une Strategy sur un symbole/mode/période ; identifié par `runId`.
- **RunEvent** — Événement versionné (RUN_STARTED, ORDER_FILLED, RUN_ENDED, etc.) append-only.
- **Deployment** — Association Strategy + mode (BACKTEST / PAPER / LIVE) + métadonnées de promotion.
- **Control Plane** — Serveur central (`trading-runtime`) : API, historique, monitoring ; n'exécute pas les ordres broker au MVP.
- **Worker Node** — Processus exécutant Strategy + broker local (mono-nœud ou distribué).
- **Gate** — Règle pass/fail pour promotion ou maintien en production.
- **Config Snapshot** — Copie immuable des paramètres au RUN_STARTED ; hash SHA-256.
- **Evidence Pack** — Export audit JSONL d'un run (events + snapshot + métadonnées).
- **Promotion** — Transition contrôlée backtest → paper → live.
- **Lifecycle State** — ACTIVE, PAUSED, RETIRED pour une Deployment.
- **StrategyQuant Import** — Conversion XML/JForex → Java via `trading-parser`.

---

## 4. Features

### 4.1 Strategy Ingestion & Authoring

**Description :** Trois voies d'entrée convergent vers le même artefact `Strategy` versionné. Realise UJ-1, UJ-4.

**Functional Requirements:**

#### FR-1: Import StrategyQuant JForex

Martin can import a **StrategyQuant export in JForex/XML format only** and obtain a compilable Java Strategy (full or partial conversion with explicit gaps report). Realizes UJ-1.

**Consequences (testable):**

- Parser rejects non-JForex/SQ formats with clear error
- Parser output lists unsupported JForex constructs
- Imported strategy appears in StrategyCatalog
- Golden backtest CI unaffected by import regressions

**Notes:** Stratégie pilote SQ : `**firstSqJforx`** (premier export JForex de référence pour le parser et golden import test).

#### FR-2: Direct Java authoring

Martin can register a hand-written Strategy in StrategyCatalog and run it via the same APIs as imported strategies. Realizes UJ-1.

**Consequences (testable):**

- New strategy discoverable via `GET /api/strategies` and RunBacktest `--list`

#### FR-3: AI-assisted ideation and codegen (Phase 3, **DeepSeek cloud**)

Martin can request AI-generated strategy hypotheses and Java skeletons via **DeepSeek API** (cloud), subject to the same validation pipeline. Realizes UJ-4.

**Consequences (testable):**

- Generated code never deploys to LIVE without passing promote gates
- AI runs tagged with `origin: AI` and `llmProvider: deepseek` in config snapshot
- DeepSeek API keys via env local (`DEEPSEEK_API_KEY`) — jamais commitées

**Out of Scope (MVP):** fully autonomous AI strategy discovery without human approval.

---

### 4.2 Backtesting & Historical Validation

**Description :** Moteur backtest unifié, données historiques, résultats reproductibles. Monte Carlo et walk-forward **non obligatoires MVP** mais architecture extensible. Realise UJ-1.

#### FR-4: Unified backtest execution

Martin can launch backtest from Control Plane or CLI with consistent fill semantics (MARKET @ open). Realizes UJ-1.

**Consequences (testable):**

- Golden backtest (LondonOpenRangeBreakout, 8760 bars, 63 trades) remains green when local historical data present
- Deterministic contract tests (`BacktestEngineContractTest`, `PlatformRobustnessTest`) pass in CI without external data
- Run events persisted before WebSocket broadcast

**État impl. (2026-05-30) :** ✅ Moteur unifié + golden réparé (story 12-10) + 16+ scénarios edge (12-11). ⚠️ Golden E2E skip en CI sans `data/historical/` — couverture CI = contract tests, pas golden intégration. Voir §8c.

#### FR-5: Config snapshot immutability

Each run stores immutable config snapshot + hash at RUN_STARTED. Realizes UJ-1, UJ-3.

**Consequences (testable):**

- `GET /api/runs/{runId}` returns `configSnapshot` and `configHash`
- Post-run config edits do not alter historical snapshot

#### FR-6: Advanced validation modules (post-MVP)

Platform supports pluggable validation: purged walk-forward, CPCV, stress tests, synthetic paths. Realizes UJ-1 (extended).

**Consequences (testable):**

- Validation modules produce pass/fail gate results stored as RunEvents
- See addendum for technique catalog

**Notes:** `[NOTE FOR PM]` Prioriser FR-6 Phase 2 après paper/live stable.

---

### 4.3 Promotion Pipeline (Backtest → Paper → Live)

**Description :** Transitions explicites avec gates ; pas de saut direct backtest → live sans paper intermédiaire `[ASSUMPTION]`. Realise UJ-2.

#### FR-7: Promote with automated gates

Martin can promote a strategy to PAPER or LIVE only when pre-defined gates pass (golden backtest, min trades, max drawdown band, **30 jours paper minimum avant LIVE**). Realizes UJ-2.

**Exception pour la famille `HARNESS`** : Les stratégies de type `HARNESS` (famille `Family.HARNESS` dans le catalogue) sont exemptées de tous les seuils de performance de backtest (`minTrades`, `maxDrawdown`, `minReturn`, `goldenBaseline` et validation par modules) pour la promotion vers `PAPER`. Un backtest complété avec succès (existence d'un `runId`) reste obligatoire afin de valider le comportement technique initial de la stratégie. Les vérifications d'identifiants de courtier (OANDA/IBKR) et de comptes s'appliquent normalement.

**Consequences (testable):**

- `POST /api/strategies/{id}/promote` returns 422 when gates fail with reasons
- DeploymentRecord stores checks[] and artifactHash

#### FR-8: Paper trading on OANDA

Martin can run strategies in PAPER mode against OANDA demo with events journalized like backtest. Realizes UJ-2.

**Gate promote → LIVE :** minimum **30 jours calendaires** en paper OANDA (ou stub labelisé `PAPER_STUB` max 30 jours en dev, puis OANDA demo obligatoire).

**Consequences (testable):**

- PAPER runs labeled distinctly from BACKTEST in events
- Promote to LIVE blocked if paper period < 30 days on same deployment
- Credentials never committed to repo

**État impl. (2026-05-30) :** ⚠️ **PAPER_STUB** = replay historique via `PaperExecutor` → délègue à `BacktestEngine` (fills MARKET @ open). **Pas** d'exécution OANDA demo. Gate paper 30j auto-pass en stub (Epic 4). Ne pas présenter paper stub comme preuve d'exécution broker. Voir addendum § Paper stub.

#### FR-9: Live forex execution

Martin can run strategies LIVE on OANDA with orders executed on worker node, not control plane. Realizes UJ-2.

**Consequences (testable):**

- Single writer to broker per account/strategy lease (Phase 2 multi-node)
- Kill switch stops new orders; action logged

---

### 4.4 Monitoring, Control Plane & Distributed Operations

**Description :** Hub central observe et historise ; workers exécutent. Realise UJ-2, UJ-3. Aligné ADR-13-07 à 13-14.

#### FR-10: Never lose a run

Platform persists all run events durably; restart recovers run status and journal. Realizes UJ-1, UJ-2.

**Consequences (testable):**

- Crash recovery test: 0 runs lost in 10 trials
- Event sequence gaps reported in API when present

#### FR-11: Network state dashboard

Martin sees all nodes (live > paper > backtest), freshness (`lastEventAt`), and alerts within 3 seconds of opening hub. Realizes UJ-2, UJ-3.

**Consequences (testable):**

- Offline node shows degraded state, not false green
- Active run on offline node flagged « état inconnu »

#### FR-12: Remote worker sync (post-MVP mono-nœud)

Martin can run workers on separate PCs syncing events to central hub with idempotent replay. Realizes UJ-2.

**Consequences (testable):**

- `(runId, eventId)` deduplication on ingest
- Local outbox replays after hub recovery

---

### 4.5 Strategy Lifecycle Governance

**Description :** Répond au pain point central — **quand tuner, pauser, retirer** — avec historique complet. Realise UJ-3.

#### FR-13: Lifecycle states

Martin can set Deployment to ACTIVE, PAUSED, or RETIRED with mandatory reason. Realizes UJ-3.

**Consequences (testable):**

- PAUSED deployment rejects new runs
- RETIRED is terminal; historical data retained

#### FR-14: Change history immuable

Every promotion, parameter change, operator action, and gate result append to audit log linked to strategyId/deploymentId. Realizes UJ-3.

**Consequences (testable):**

- Evidence pack export includes operator actions
- No silent overwrite of prior config versions

#### FR-15: Drift and performance signals

Platform surfaces signals when live/paper performance deviates from **baseline backtest** (config snapshot au promote) beyond configurable thresholds. Realizes UJ-3.

**Fenêtre d'évaluation :** 30 jours glissants (minimum **14 jours** ou **20 trades** avant tout signal — sinon **HOLD**).

**Seuils MVP par défaut** (party mode consensus — overridable YAML) :


| Signal              | Condition                                        | Recommandation            |
| ------------------- | ------------------------------------------------ | ------------------------- |
| Drawdown            | Live DD ≥ 1,5× DD backtest                       | REVIEW_PARAMS             |
| Drawdown            | Live DD ≥ 2,0× DD backtest, ou > DD max backtest | PAUSE                     |
| Drawdown            | ≥ 2,5× pendant 7 jours, ou 3× PAUSE en 60 j      | RETIRE                    |
| Win rate            | Live < backtest − 15 pts (min. 15 trades)        | REVIEW_PARAMS             |
| Win rate            | Live < backtest − 25 pts                         | PAUSE                     |
| Volume trades       | < 50 % du rythme backtest attendu                | REVIEW_PARAMS             |
| Absence trades      | 0 trade pendant 2× intervalle attendu            | REVIEW_PARAMS             |
| Absence trades      | 3× intervalle attendu                            | PAUSE                     |
| Config              | Hash config ≠ snapshot promote                   | PAUSE (immédiat)          |
| Gaps events         | > 3 gaps ou > 15 min cumulés                     | warning                   |
| RETIRE (long terme) | Sous-perf 90j + DD > 25 % (Mary)                 | RETIRE — **Phase 2 only** |


**Règle composite :** 1 dimension rouge → REVIEW_PARAMS ; **2 dimensions rouges → PAUSE**.

**Baseline :** métriques `RUN_ENDED` du backtest de promotion + re-backtest roulant mensuel (Phase 2).

**Consequences (testable):**

- Dashboard shows recommendation: HOLD / REVIEW_PARAMS / PAUSE / RETIRE
- Each signal logs metric, value, threshold, timestamp UTC
- Recommendations are advisory; operator decision always logged

**Non calculable MVP sans nouveaux events :** slippage réel, spread live, latence broker (Phase 2).

#### FR-16: Parameter retune workflow

Martin can fork a new config version, backtest it, and promote only via gates — never silent hot-swap on LIVE. Realizes UJ-3.

**Consequences (testable):**

- LIVE deployment references pinned configHash until explicit promote

---

### 4.6 Research & Genetics (Existing Module Extension)

**Description :** `trading-genetics` fournit recherche paramétrique, robustness scores, batch runs — intégration au catalog et gates. Realise UJ-1.

#### FR-17: Genetics integration

Martin can export genetic search winners to StrategyCatalog candidates subject to FR-7 gates. Realizes UJ-1.

**Consequences (testable):**

- Genetic winners tagged `origin: GENETICS` in snapshot

---

## 5. Non-Goals (Explicit)

- Plateforme SaaS multi-tenant
- Trading actions US/EU en v1 (forex only)
- IBKR live connector v1
- Monte Carlo / walk-forward obligatoires au MVP
- Kubernetes / microservices obligatoires
- LLM trading autonome sans approbation humaine
- Optimisation HFT / latence sub-milliseconde

---

## 6. MVP Scope

### 6.1 In Scope (MVP — ~Sprint 13–16)

- Control plane HTTP + WebSocket (`trading-runtime`)
- Backtest via API + CLI unifié
- Event store durable + config snapshot + gap detection
- StrategyCatalog (Java + examples + prop strategies existantes)
- Promote gates basiques backtest → paper (stub puis OANDA demo)
- Dashboard état runs + fraîcheur
- Lifecycle PAUSE/RETIRE + OPERATOR_ACTION events
- Import SQ parser + conversion `**firstSqJforx`** (golden import test)
- Mono-nœud fiable ; sync multi-PC Phase 2

### 6.2 Out of Scope for MVP


| Item                              | Raison                                     |
| --------------------------------- | ------------------------------------------ |
| CPCV, purged WFA, synthetic paths | Post-MVP validation world class (addendum) |
| AI codegen                        | Phase 3 — même gates requis                |
| Actions / multi-broker            | Forex first                                |
| Multi-user RBAC                   | Solo operator v1                           |
| mTLS multi-nœud                   | Sprint 16+                                 |
| Evidence pack ZIP                 | Sprint 14                                  |


---

## 7. Success Metrics

**Primary**

- **SM-1**: 0 run perdu sur 10 tests crash/restart — Validates FR-10
- **SM-2**: Martin lance backtest + paper sans Maven manuel en < 5 min — Validates FR-4, FR-8
- **SM-3**: 100 % des promotions LIVE passent par gate documenté — Validates FR-7

**Secondary**

- **SM-4**: Temps de décision pause/retire < 2 min grâce signaux dashboard — Validates FR-15
- **SM-5**: Import SQ `**firstSqJforx`** convertie et backtestée — Validates FR-1

**Counter-metrics (do not optimize)**

- **SM-C1**: Nombre de stratégies LIVE simultanées — ne pas maximiser ; focus qualité
- **SM-C2**: Sharpe backtest brut — ne pas optimiser sans gates ; évite overfitting

---

## 8. Decisions (resolved)


| #   | Question                       | Décision                                                     |
| --- | ------------------------------ | ------------------------------------------------------------ |
| 1   | Seuils FR-15                   | **Party mode** — tableau § FR-15 (defaults MVP)              |
| 2   | Stratégie SQ pilote            | `**firstSqJforx`** — export JForex de référence parser       |
| 3   | Durée paper avant LIVE         | **30 jours calendaires** minimum OANDA demo                  |
| 4   | AI codegen                     | **DeepSeek** cloud API ; clé `DEEPSEEK_API_KEY` en env local |
| 5   | Format stratégies externes     | **SQ / JForex XML uniquement**                               |
| 6   | Seuils RETIRE long terme (90j) | **Phase 2** — hors MVP                                       |


## 8b. Open Questions (remaining)

*Aucune bloquante pour MVP — revisiter à Phase 2 (seuils RETIRE 90j) et Phase 3 (modèle DeepSeek exact, ex. deepseek-chat vs coder).*

---

## 8c. État d'implémentation (actualisation 2026-05-30)

_Source : sprint-status, stories 12-1→12-11, Epic 13, party mode PRD 2026-05-30._

| Domaine | PRD / FR | Statut réel | Notes |
|---------|----------|-------------|-------|
| Backtest unifié | FR-4 | **Done** (Epic 12) | CLI `RunBacktest`, `RunContext`, MARKET @ open documenté |
| Confiance backtest | FR-4 | **Renforcé** (12-10, 12-11) | Golden baseline réparée ; contract + platform tests déterministes |
| Golden CI | FR-4 | **Partiel** | Skip sans `data/historical/` ; contract tests toujours verts |
| Event store / runs | FR-10 | **Done** (13-2, 13-3) | SQLite, HTTP control plane |
| Promote gates | FR-7 | **Scaffold** (13-5) | `PromoteService` ; pas enforced end-to-end avec OANDA |
| Run lifecycle | FR-13 | **Review** (13-9) | Interface en cours de stabilisation |
| Paper OANDA | FR-8 | **Stub** (12-6) | = backtest replay ; Epic 4 requis pour demo réelle |
| Live OANDA | FR-9 | **Absent** | `RunManager` rejette LIVE |
| Parser SQ | FR-1 | **Backlog** (Epic 2) | `firstSqJforx` non livré |
| Drift / retune | FR-15, FR-16 | **Backlog** | Epic 17+ |
| Dashboard / TUI | FR-11 | **Backlog** | 13-6, 13-7 |
| Validation avancée | FR-6 | **Backlog** | CPCV, purged WFA — post-MVP inchangé |

**Découvertes terrain à intégrer au backlog :**

1. **Corruption baseline golden** (12-10) — risque si une seule stratégie porte la confiance ; mitigé par contract tests.
2. **Paper ≠ broker** — distinction obligatoire dans toute communication externe (prop-firm, investisseur).
3. **Catalog `TestStrategies`** — harness reproductible pour régression moteur sans données historiques.

---

## 8d. Évaluation prop-firm (honest, 2026-05-30)

Verdict party mode : **démonstration interne OK — soumission formelle prop-firm : non.**

| Critère prop-firm typique | Statut | Preuve / gap |
|---------------------------|--------|--------------|
| Backtest reproductible | ✅ | Contract tests, golden local, events immuables |
| Audit trail décisions | ✅ | RunRecord, DeploymentStore, config snapshot (FR-5) |
| Gates research → production | ⚠️ | Code présent ; LIVE bloqué ; paper stub |
| Modèle d'exécution réaliste | ❌ | Fills bar.open ; slippage optionnel ; pas latence/rejets |
| Walk-forward / OOS rigoureux | ❌ | FR-6 Phase 2+ |
| Risk limits temps réel (daily DD, etc.) | ❌ | RunLifecycle sans guards drawdown live |
| Paper / live prouvé sur broker | ❌ | Epic 4 |
| CI garantit non-régression backtest | ⚠️ | Contract tests oui ; golden E2E conditionnel |

**Position produit :** Trading Bridge possède les **prérequis** d'une plateforme prop-grade (moteur déterministe, journal, gates). Il **ne remplace pas** encore une due diligence prop-firm tant que paper/live broker et validation OOS ne sont pas livrés.

---

## 9. Assumptions Index

- **[ASSUMPTION]** Persona solo Martin — pas d'équipe multi-user v1
- **[ASSUMPTION]** Outil interne, pas lancement SaaS public
- **[ASSUMPTION]** Paper obligatoire avant LIVE (pas de skip)
- **[ASSUMPTION]** AI codegen Phase 3, pas MVP
- **[ASSUMPTION]** Règles drift MVP — seuils party mode § FR-15 (overridable)
- **[DECISION]** Paper 30 j minimum avant LIVE
- **[DECISION]** Import externe : SQ JForex only
- **[DECISION]** AI DeepSeek cloud Phase 3
- **[DECISION]** Pilote parser SQ : `firstSqJforx`
- **[DECISION]** Seuils RETIRE 90j → Phase 2
- **[ASSUMPTION]** Actions/bourse = v2 après forex stable

---

## 10. Cross-Cutting NFRs

- **Time:** UTC (`Instant`) partout ; affichage Toronto optionnel
- **Security:** pas de credentials en repo ; env / fichiers locaux ignorés git
- **Reliability:** promesse MVP « ne jamais perdre un run » > always-on hub
- **Observability:** events append-only ; fraîcheur UI prouvée, pas voyant vert mensonger
- **Audit:** OPERATOR_ACTION + config snapshot immuable par run

---

## 11. Constraints and Guardrails

- **Safety:** kill switch ; pause empêche nouveaux ordres ; LIVE requires gates
- **Privacy:** stratégies et clés API restent locales
- **Cost:** pas d'infra cloud obligatoire MVP ; SQLite + PCs existants

---

## 12. Audit Trail / Decision Provenance

Aligné FR-14 : journal append-only, evidence pack, chaîne causale run → ordre → fill (`correlationId` Phase 2). Voir ADR-13-14.

---

## 13. Roadmap Phases (indicatif)


| Phase            | Focus                                                                                  | FR clés                 |
| ---------------- | -------------------------------------------------------------------------------------- | ----------------------- |
| **MVP (S13–16)** | Control plane, durable runs, gates, paper/live forex, lifecycle, import `firstSqJforx` | FR-1, FR-4–11, FR-13–14 |
| **Phase 2**      | Validation avancée, drift rules, broker reconcile, multi-PC                            | FR-6, FR-12, FR-15–16   |
| **Phase 3**      | AI codegen, CPCV, genetics deep integration                                            | FR-3, FR-6, FR-17       |
| **Phase 4**      | Actions, IBKR, multi-user                                                              | hors MVP                |
