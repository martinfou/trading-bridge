# Addendum — PRD Trading Bridge E2E

_Détail technique et recherche qui ne appartient pas au corps du PRD._

## Références architecture existantes

- `_bmad-output/planning-artifacts/adr-13-distributed-platform.md` — hub passif, nœuds, historique
- `_bmad-output/planning-artifacts/architecture-epic-13-platform-runtime.md` — control plane, promote gates
- `_bmad-output/brainstorming/brainstorming-session-2026-05-24-1430.md`

## Pipeline de validation « world class » (post-MVP)

Recherche synthétisée — pratiques au-delà de Monte Carlo et walk-forward naïf :

| Technique | Objectif | Priorité suggérée |
|-----------|----------|-------------------|
| **IS → Purged WFA → OOS holdout** | Séparation stricte ; paramètres verrouillés en OOS | Phase 2 |
| **Purge gaps** | Éviter leakage pour stratégies stateful (positions, trailing stops) | Phase 2 |
| **CPCV** (Combinatorial Purged CV) | Distribution de perf OOS, pas un score unique | Phase 3 |
| **Decision gates** | Majority-pass + veto catastrophique (drawdown, violation contraintes) | Phase 2 |
| **Parameter stability regions** | Choisir plateau robuste, pas pic fragile | Phase 3 |
| **Stress testing exécution** | Spread/commission/slippage dégradés, circuit breakers | Phase 2 |
| **Synthetic path resampling** | Tester sur chemins alternatifs (FFR, block bootstrap) | Phase 3 |
| **Deflated Sharpe / PSR / DSR** | Corriger biais de sélection multiple | Phase 3 |
| **Meta-overfitting discipline** | Journal des idées testées ; holdout final jamais touché | Processus |
| **Réconciliation broker ↔ journal** | Ghost journal prevention | Phase 2 (live) |

Sources : Lopez de Prado (CPCV, purged k-fold) ; Quant Beckman robust optimization protocol ; AlgoXpert IS–WFA–OOS framework (arXiv:2603.09219) ; Anton Vorobets synthetic path backtesting.

## Mécanismes transport (distribué)

Voir ADR-13-10 : HTTP JSON, SQLite, idempotence `(runId, eventId)`, phased rollout mono-nœud d'abord.

## AI strategy generation (Phase 3+)

- **Provider :** DeepSeek API (`DEEPSEEK_API_KEY` en env)
- Idéation : LLM propose hypothèses + contraintes (symbole, session, risque)
- Codegen : génération Java implémentant `Strategy` + tests smoke backtest
- Gates obligatoires : même pipeline validation qu'une stratégie importée — pas de bypass paper/live

## Parser SQ pilote

- **Stratégie de référence :** `firstSqJforx` (export JForex StrategyQuant)
- Golden test import : conversion + backtest CI

## Paper stub vs broker paper (2026-05-30)

**Décision documentaire (party mode) :** le mode PAPER v1 (`PaperExecutor`) **n'est pas** une simulation broker.

| Aspect | PAPER_STUB (v1) | Paper OANDA (FR-8 cible) |
|--------|-----------------|--------------------------|
| Moteur | `BacktestEngine` replay bars | Ordres REST demo OANDA |
| Fills | MARKET @ `bar.open()` | Prix marché / rejets réels |
| Latence | Aucune | Réseau + rate limits |
| Gate 30j paper | Auto-pass labelisé stub | Compteur calendaire réel |
| Usage | Dev, tests plateforme | Preuve avant LIVE |

**Implication prop-firm :** un rapport « paper PnL » produit par le stub **ne doit pas** être présenté comme track record d'exécution. Epic 4 requis.

## Backtest trust & platform test harness (2026-05-30)

Stories **12-10** et **12-11** — détail technique post-incident baseline :

| Artefact | Rôle |
|----------|------|
| `GoldenBacktestTest` | E2E LORB + données locales ; baseline full-precision |
| `BacktestEngineContractTest` | MARKET@open, SL/TP, invariants comptables |
| `PlatformRobustnessTest` | 16+ scénarios normal + edge ; parité BACKTEST/PAPER |
| `TestStrategies` / `TestBars` | Catalogue déterministe sans random |

**Incident 12-10 :** constantes golden corrompues (61 trades / 0,14 % vs 63 / 16,44 %) — preuve que golden seul est insuffisant sans contract tests et garde return↔PnL.

**CI :** `GoldenBacktestTest` skip sans data ; **`PlatformRobustnessTest` + `BacktestEngineContractTest` toujours exécutés.**

Réf. : `docs/testing.md`, `_bmad-output/implementation-artifacts/12-10-backtest-engine-trust.md`, `12-11-platform-test-strategies.md`.
