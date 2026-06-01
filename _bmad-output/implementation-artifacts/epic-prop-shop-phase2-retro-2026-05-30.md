# Rétrospective — Prop-shop Phase 2 (Epics 15, 16, 17, 19)

**Date :** 2026-05-30  
**Project Lead :** Martin Fournier  
**Commit de clôture :** `e1c7839` — Add prop-shop Phase 2 runtime: brokers, gates, drift, and validation.

---

## Epics couverts

| Epic | Titre | Stories done |
|------|-------|--------------|
| **15** | Validation MVP & promote gates | 15-5 → 15-8 |
| **16** | Exécution broker (OANDA → IBKR) | 16-2 → 16-10 |
| **17** | Salle de contrôle | 17-9 → 17-12 |
| **19** | Validation statistique avancée | 19-4, 19-5 |

**Livrable commun :** control plane capable de promouvoir avec gates, exécuter en paper broker (OANDA/IBKR), monitorer drift post-broker, et valider via modules OOS holdout + execution stress.

---

## Ce qui a bien fonctionné

**Architecture extensible (Epic 19 → 15)**  
Amelia (Developer) : le SPI `ValidationModule` + configs JSON (`oos-holdout.json`, `execution-stress.json`) permet d’ajouter des gates sans toucher `PromoteService` à chaque fois.

**Label d’exécution unifié (Epic 15 / 17)**  
`ExecutionLabel` + catalog UI/API donnent une source de vérité pour evidence export, deployments et control summary — réduit la confusion stub vs broker.

**Broker en couches (Epic 16)**  
Séparation `trading-broker` (interface) / `trading-data` (clients OANDA/IBKR) / `trading-runtime` (RunManager, BrokerRunExecutor) — tests avec stubs (`IBKR_USE_STUB`, `StubOandaRestClient`) sans credentials.

**Revue de code structurée**  
Blind Hunter + Edge Case Hunter ont surface des vrais gaps (audit trail, IBKR/LIVE path, drift edge cases) ; patches appliqués avant commit.

**Tests d’intégration runtime**  
`PromoteServiceTest`, `ControlPlaneServerTest`, modules validation — suite verte sur `mvn test -pl trading-runtime -am`.

---

## Défis et frictions

**Chemin IBKR vs chemin LIVE (Epic 16 / 15)**  
Charlie (Senior Dev) : `PAPER_IBKR` promote OK, mais `countsTowardPaperPeriod()` reste OANDA-only et LIVE → `LIVE_OANDA`. Décision **1A** : MVP documenté, pas de fausse promesse LIVE IBKR.

**Holdout OOS vs métriques source (Epic 19)**  
Alice (Product Owner) : le backtest source pour `golden_baseline` inclut encore la fenêtre holdout. Décision **1C/2A** : MVP accepté, doc dans `docs/testing.md`.

**Volume de changements**  
Commit unique ~159 fichiers — difficile à reviewer en une passe ; risque de mélanger consolidation (Epic 12/13) et prop-shop si non découpé à l’avenir.

**Control plane sans auth**  
Dana (QA) : `/promote` et `/kill` ouverts sur localhost — acceptable pour dev local, dette explicite avant exposition réseau.

**Reprise PAUSED / stop RUNNING**  
Edge case RunManager : resume PAUSED ne relance pas l’executor ; stop RUNNING renvoie exception — documenté en dette différée.

---

## Insights clés

1. **Gates ≠ modules** — `validationModuleEnabled` + modules `enabled: false` doit être explicite (skip vs fail) ; décision produit enregistrée.
2. **Audit trail = flush after promote** — `ValidationAuditBuffer` évite des events OOS/stress sur promote rejeté.
3. **Drift broker-only** — BACKTEST/PAPER_STUB → `INSUFFICIENT` ; évite faux signaux, mais exige déploiement broker pour FR-15.
4. **Configs runtime versionnables** — `data/runtime/*.json` + env overrides = bon pattern ops ; manque encore validation schema stricte (ex. `rollingWindowDays`).

---

## Dette technique acceptée (defer CR)

| Item | Impact | Epic suivant |
|------|--------|--------------|
| Auth control plane | Sécurité si bind non-local | 13.7 dashboard / ops |
| Promote concurrent non atomique | Double save deployment | rare en single-operator |
| Kill switch non persisté | Reprise post-restart JVM | 16.x hardening |
| WebSocket replay cap 1000 | Runs longs incomplets WS | 13.4 |
| IBKR paper → LIVE path | Opérateur IBKR-only bloqué LIVE | story dédiée |
| Holdout IS/OOS strict | Sur-optimisme possible promote | Epic 19 extension |
| PAUSED resume broker runs | Run stall après pause | 13.9 lifecycle |

---

## Préparation — Epics suivants (12 / 13)

**Epic 12 (backlog restant)**  
- 12-7 strategy contract home policy  
- 12-8 shared indicators  
- 12-9 docs/agents alignment  

**Epic 13 (review + backlog)**  
- Clôturer review : 13-9 lifecycle, 13-9 commission/slippage, 13-8 mini-dataset CI  
- Puis : 13-6 TUI, 13-7 dashboard Laravel, 13-8 heartbeat  

**Dépendances prop-shop → 13**  
Control plane, event store, promote gates et broker executor sont la base pour TUI/dashboard v1.

---

## Action items

| # | Action | Owner | Critère de succès |
|---|--------|-------|-------------------|
| A1 | Story « IBKR paper → LIVE path » ou formaliser OANDA-only dans runbook | Alice (PM) | PRD/runbook à jour |
| A2 | Persist kill switch flags (fichier ou event store) | Charlie (Dev) | Kill survit restart JVM |
| A3 | Clôturer stories Epic 13 en review (mini-dataset, lifecycle, slippage) | Amelia (Dev) | `review` → `done` + tests CI |
| A4 | Epic 12-7/12-8 — strategy contract + indicators partagés | Amelia (Dev) | `mvn clean install` vert |
| A5 | Auth middleware control plane (token ou mTLS local) | Charlie (Dev) | promote/kill rejetés sans creds |
| A6 | `minHoldoutTrades` gate (option post-2A) | Alice (PM) | spec Epic 19 si priorisé |

---

## Accords d’équipe

- Toute nouvelle gate validation doit journaliser via `ValidationAuditBuffer` (flush post-promote).
- Décisions MVP (1A, 1C, 2A) documentées dans `docs/testing.md` avant merge.
- Pas de commit `target/` ; configs sample dans `data/runtime/` uniquement sans secrets.
- Code review adversariale avant `done` sur epics broker/runtime.

---

## Évaluation de readiness

| Dimension | Statut |
|-----------|--------|
| Tests unitaires/intégration | ✅ `trading-runtime` vert |
| Golden backtest local | ⚠️ skip si pas `data/historical/` |
| Deploy production | ❌ out of scope — local control plane |
| Stakeholder sign-off | ✅ Martin (Project Lead) |
| Dette critique bloquante | ❌ aucune pour démarrer Epic 12/13 |

**Verdict :** Phase 2 prop-shop **complete** pour usage dev/local ; préparation Epic 12/13 recommandée avant dashboard/TUI.

---

## Participants (party mode)

- **Amelia (Developer)** — facilitation, implémentation runtime  
- **Alice (Product Owner)** — gates, MVP IBKR/LIVE  
- **Charlie (Senior Dev)** — broker, dette technique  
- **Dana (QA)** — tests promote/validation/drift  
- **Martin Fournier (Project Lead)** — décisions 1A/2A, commit `e1c7839`
