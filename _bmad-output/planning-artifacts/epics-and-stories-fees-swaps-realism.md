# Epics & Stories — Réalisme des frais et swaps (Fees & Swaps Realism)

> **Source**: `_bmad-output/planning-artifacts/fees-swaps-realism-report.md` (audit 2026-08-12)
> **Statut**: CRITIQUE — le batch du 30-31 juillet 2026 (4 094 runs à $1 000) a tourné avec `BacktestExecutionCost.ZERO` (commission $0, slippage $0, swap $0). Les rendements publiés (+197.8% GBP_USD, +210% EmaPullbackContinuation) sont des artefacts sans coûts.
> **Preuves**: `RunContext.java:106` hardcode ZERO; SQLite `events.db:backtest_runs` confirme `total_commission=0.0` sur 4 627/4 682 runs à $1 000; `SwapCalculator.java:22-40` taux statiques 2024-2026 sur données 2010-2025; `ForexPnL.java:7` USD/JPY fixe = 150; `BacktestEngine.java:469-489` pas de swap sur SL/TP.

---

# Epic 39 — Câblage des coûts dans le moteur (Cost Wiring Correctness)

**Objectif**: S'assurer que chaque backtest applique réellement des frais réalistes, que le swap est compté sur toutes les sorties, et que les coûts sont persistés et auditable.

## User Stories (valeur métier)

| # | As a [role]... | Value |
|---|----------------|-------|
| US-39.1 | En tant que trader, je veux que tous les backtests incluent les frais réels (commission, slippage, swap) pour que les classements de stratégies reflètent la réalité, pas des artefacts zéro-coût. | Sans ça, aucun résultat publié n'est fiable et on pourrait paper-trader une stratégie qui perd en réel. |
| US-39.2 | En tant que trader, je veux que le swap soit compté quand une position sort par stop-loss, take-profit ou fin de backtest, pas seulement sur les sorties `closeOnly()`. | Les positions longues fermées sur SL (les plus fréquentes en drawdown) n'accumulent AUCUN swap aujourd'hui : coût de détention sous-estimé. |
| US-39.3 | En tant que trader, je veux que les coûts de swap soient persistés dans la base SQLite pour auditer chaque run et comparer les modèles de coûts. | Sans colonne `total_swap`, impossible de vérifier l'impact du swap sur les résultats. |

## Coding Stories (technical tasks)

| # | Story | Effort | Dépend de | Description |
|---|-------|--------|-----------|-------------|
| 39.1 | Fixer le coût par défaut dans `RunContext.forStrategy()` | XS | US-39.1 | Remplacer `BacktestExecutionCost.ZERO` (ligne 106) par un coût par défaut non-zéro (ex: commission $0.07 + slippage 0.5 pip). Garder la possibilité de passer ZERO explicitement pour les tests de sensibilité. |
| 39.2 | Ajouter le calcul de swap dans `closePosition()` | S | US-39.2 | Copier l'appel `SwapCalculator.calculateSwap()` présent dans `reduceOppositeSide()` (lignes 371-374) vers `closePosition()` (lignes 469-489) et `closeRemainingPositions()`. |
| 39.3 | Ajouter `total_swap` au schéma SQLite + persistance | XS | US-39.3 | Migration `ALTER TABLE backtest_runs ADD COLUMN total_swap REAL NOT NULL DEFAULT 0.0` + mise à jour de `BacktestPersistenceService`. |

## Détails d'implémentation

| Story | Fichiers cibles | Critères d'acceptation | Tests |
|-------|----------------|------------------------|-------|
| 39.1 | `trading-backtest/src/main/java/com/martinfou/trading/backtest/RunContext.java:106` (modifier), `trading-backtest/src/main/java/com/martinfou/trading/backtest/BacktestExecutionCost.java` (vérifier constantes) | — Un run via `RunAllBatchBacktests` persiste `total_commission > 0`<br/>— Les runners ciblés existants peuvent toujours forcer ZERO si besoin<br/>— Aucune stratégie ne change de comportement d'entrée/sortie | `RunContextTest`: le coût par défaut de `forStrategy()` n'est pas `BacktestExecutionCost.ZERO` |
| 39.2 | `trading-backtest/src/main/java/com/martinfou/trading/backtest/BacktestEngine.java:469-489` (modifier), `:371-374` (référence) | — Un trade fermé sur SL accumule `totalSwap` correct<br/>— Fin de backtest (`closeRemainingPositions`) accumule le swap<br/>— Mercredi triple conservé | `BacktestEngineTest`: trade SL avec détention 5 jours → swap attendu; trade force-close → swap attendu |
| 39.3 | `data/runtime/events.db` (migration), `trading-backtest/.../BacktestPersistenceService.java` (modifier) | — Colonne `total_swap` existe avec défaut 0.0<br/>— Nouveau run persiste `total_swap != 0` quand applicable<br/>— Les anciens runs lisibles (défaut 0) | SQL: `PRAGMA table_info(backtest_runs)` contient `total_swap`; requête après run: `SELECT total_swap FROM backtest_runs ORDER BY created_at DESC LIMIT 1` |

---

# Epic 40 — Modèle de coûts réaliste (Realistic Cost Model)

**Objectif**: Modéliser les coûts comme un vrai broker : spread bid/ask par paire, swaps qui suivent les taux directeurs historiques, conversion JPY avec le taux historique réel.

## User Stories (valeur métier)

| # | As a [role]... | Value |
|---|----------------|-------|
| US-40.1 | En tant que trader, je veux un spread bid/ask réaliste par paire pour que les stratégies haute fréquence (2 000+ trades) ne soient plus surévaluées de ~$900 sur la période. | Le spread est le coût dominant chez un broker spread-only (OANDA). Sans lui, les scalpers semblent rentables à tort. |
| US-40.2 | En tant que trader, je veux des swaps qui varient selon la période historique pour que les stratégies carry (AUD/JPY, NZD/JPY) aient un PnL réel, pas fabriqué. | Les taux statiques 2024-2026 sur données 2010-2025 surévaluent le carry de 3-6x sur les périodes à taux bas. |
| US-40.3 | En tant que trader, je veux une conversion JPY basée sur le taux USD/JPY réel de chaque période pour des PnL et swaps corrects sur les paires JPY. | USD/JPY fixe à 150 vs ~80 en 2012 = erreur 1.5-1.9x sur les paires en JPY. |

## Coding Stories (technical tasks)

| # | Story | Effort | Dépend de | Description |
|---|-------|--------|-----------|-------------|
| 40.1 | Ajouter le modèle de spread bid/ask par paire | M | US-40.1, 39.1 | Nouveau `spreadPips` par paire dans `BacktestExecutionCost`; les fills BUY se font au ask (prix + spread/2), SELL au bid (prix - spread/2). Ajouter une constante préconfigurée type `OANDA_SPREAD_ONLY` et `IBKR_ECN`. |
| 40.2 | Swaps variables dans le temps | L | US-40.2 | Remplacer la map statique `SWAP_RATES` par des taux par période (Option A: buckets 2010-2015 / 2016-2019 / 2020-2022 / 2023-2026) ou par calcul à partir du différentiel de taux directeurs (Option B: CSV des taux Fed/ECB/BOE/BOJ/RBA/RBNZ/BOC/SNB, ~trimestriel). |
| 40.3 | Conversion USD/JPY historique | M | US-40.3 | Tracker le taux USD/JPY courant depuis le flux de barres (`BacktestEngine.run()`: si bar USD_JPY → `currentUsdJpyRate = bar.close()`) et le passer à `SwapCalculator` et `ForexPnL`. |

## Détails d'implémentation

| Story | Fichiers cibles | Critères d'acceptation | Tests |
|-------|----------------|------------------------|-------|
| 40.1 | `trading-backtest/src/main/java/com/martinfou/trading/backtest/BacktestExecutionCost.java` (modifier), `BacktestEngine.java:523-527` (modifier les fills) | — BUY rempli à ask, SELL à bid (vérifiable sur trades persistés)<br/>— Constantes OANDA (spread 0.6-1.5 pips, 0 commission) et IBKR (spread 0.1-0.3 + commission) disponibles<br/>— Coût round-trip EUR/USD 1k ≈ $0.12-0.30 | `BacktestEngineTest`: fill BUY = prix + spread/2; fill SELL = prix - spread/2; coût total sur 100 trades = somme attendue |
| 40.2 | `trading-backtest/src/main/java/com/martinfou/trading/backtest/SwapCalculator.java` (réécrire), `src/main/resources/` (CSV taux directeurs si Option B) | — Swap AUD/JPY long en 2011 ≈ +1-2 pips/jour (pas +6.5)<br/>— Swap USD_JPY long en 2023 > en 2015<br/>— Mercredi triple conservé<br/>— Paires couvertes: EUR_USD, GBP_USD, USD_JPY, AUD_USD, NZD_USD, USDCAD, USD_CHF, GBP_JPY, EUR_GBP, AUD_JPY, NZD_JPY, EUR_JPY, XAU_USD | `SwapCalculatorTest`: swap 2011 vs 2024 diffèrent; swap mercredi = 3x; `getAnnualCarry` cohérent |
| 40.3 | `trading-backtest/src/main/java/com/martinfou/trading/backtest/BacktestEngine.java` (modifier), `trading-core/.../ForexPnL.java` (modifier le défaut), `SwapCalculator.java:80-85` (modifier) | — USD/JPY ~80 utilisé pour les trades de 2012, ~150 pour 2024<br/>— Aucun hardcode `DEFAULT_USD_JPY=150` dans les calculs de PnL/swap quand le flux est dispo<br/>— Fallback au défaut si USD_JPY absent des données | `SwapCalculatorTest`: même position en 2012 vs 2024 → valeurs différentes selon le taux; `ForexPnLTest` |

---

# Epic 41 — Re-baseline & validation (Re-baseline & Validation)

**Objectif**: Recalculer tous les classements avec les coûts réalistes et valider le modèle contre les vrais coûts broker.

## User Stories (valeur métier)

| # | As a [role]... | Value |
|---|----------------|-------|
| US-41.1 | En tant que trader, je veux un classement des stratégies recalculé avec les coûts réalistes pour savoir lesquelles paper-trader. | Le top 5 actuel (+197%, +210%) est potentiellement invalide. Le prochain classement doit être publiable. |
| US-41.2 | En tant que trader, je veux que le modèle de coûts soit validé contre les coûts réels d'un broker (OANDA/IBKR) pour lui faire confiance. | Un modèle non validé peut être aussi trompeur qu'un modèle absent. |

## Coding Stories (technical tasks)

| # | Story | Effort | Dépend de | Description |
|---|-------|--------|-----------|-------------|
| 41.1 | Re-run du batch complet avec les coûts fixes | M | US-41.1, 39.1, 40.1 | `RunAllBatchBacktests` avec le nouveau coût par défaut (commission + slippage + spread si dispo). 4-8h, background. Comparer avant/après: % de stratégies qui passent le gate PF ≥ 1.2, Sharpe ≥ 0.5, trades ≥ 30. |
| 41.2 | Validation: tests unitaires + comparaison broker | S | US-41.2, 40.2, 40.3 | Tests: swap avec différents USD/JPY, fills spreadés, swap sur SL/TP, coût par défaut non-zéro. Comparaison: top 5 sur 1 an avec coûts OANDA vs données de spread OANDA historiques, tolérance 10-15%. |

## Détails d'implémentation

| Story | Fichiers cibles | Critères d'acceptation | Tests |
|-------|----------------|------------------------|-------|
| 41.1 | `trading-examples/.../RunAllBatchBacktests.java` (runner), `batch-results/` (outputs) | — Nouveau `profitable_report.txt` avec coûts<br/>— Tableau avant/après par stratégie (net profit, PF, Sharpe)<br/>— Compte des stratégies passant le gate avant vs après<br/>— Résultats persistés avec `total_commission > 0` | `grep -c "Failed to persist" batch-results/full_run_*.log` = faible; SQLite: `SELECT COUNT(*) FROM backtest_runs WHERE total_commission = 0` = 0 sur le nouveau batch |
| 41.2 | `trading-backtest/src/test/java/com/martinfou/trading/backtest/SwapCalculatorTest.java` (modifier), `BacktestEngineTest.java` (modifier), `docs/fees-swaps-validation.md` (créer) | — 4 tests unitaires verts (swap USD/JPY variable, fills spreadés, swap SL/TP, coût défaut)<br/>— Écart top 5 vs données OANDA dans 10-15%<br/>— Rapport de validation dans docs/ | `mvn test -pl trading-backtest` passe |

---

## Build Order (ordre de priorité)

| Ordre | Story | Effort | Pourquoi |
|-------|-------|--------|----------|
| 1 | **39.1** (coût par défaut non-zéro) | XS | 1 heure, débloque tout le reste |
| 2 | **39.3** (total_swap en SQLite) | XS | Persister avant de re-run |
| 3 | **39.2** (swap sur SL/TP) | S | Corrige la sous-estimation systématique |
| 4 | **40.1** (spread bid/ask) | M | Le coût dominant manquant |
| 5 | **40.3** (USD/JPY historique) | M | Erreur 1.5-1.9x sur les paires JPY |
| 6 | **40.2** (swaps variables) | L | Précision carry, plus complexe |
| 7 | **41.1** (re-run batch) | M | Nouveau classement publiable |
| 8 | **41.2** (validation broker) | S | Confiance dans le modèle |

**Estimation totale**: ~9 jours-homme. **Première livraison de valeur**: story 39.1 (1h) rend tous les futurs backtests réalistes.
