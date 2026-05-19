# BMAD Sprint — Advanced Backtesting & Portfolio Analytics

> Sprint 6 — Généré par Bmad Sprint Planning
> Martin Fournier — 19 mai 2026

---

## 🎯 Objectif du Sprint

Ajouter des capacités avancées de backtesting : Monte Carlo, Walk-Forward Optimization,
matrice de corrélation, portfolio builder, et rapports de type StrategyQuant.

Ces features transforment le moteur d'un simple backtest unitaire en un véritable
outil d'analyse quantitative.

---

## 🌐 Questions de Scope (Bmad)

### Q1: Quel périmètre pour Monte Carlo ?

- **a)** ✅ Simulation simple : randomisation de l'ordre des trades, 1000 runs,
       distribution P&L / drawdown / Sharpe, percentiles (best/worst/median/5%/95%)
- **b)** With additional: analyse de sensibilité sur les paramètres d'entrée
       (commission, slippage, taille de position)
- **c)** Full: a) + b) + stress testing par scénario (crash 2008, COVID, etc.)

### Q2: Walk-Forward Optimization — quelle profondeur ?

- **a)** ✅ Simple: une seule fenêtre IS/OOS, optimisation brute des paramètres,
       validation sur OOS
- **b)** Multiple: fenêtres glissantes (ex: 12 mois IS / 3 mois OOS, slide 3 mois),
       moyenne des résultats OOS
- **c)** Full: b) + optimisation multi-objectif (Sharpe + Drawdown + Profit Factor),
       sélection de modèle (AIC/BIC)

### Q3: Correlation Matrix — scope ?

- **a)** ✅ Corrélation des P&L quotidiens entre stratégies (Pearson)
- **b)** ✅ Also: corrélation des drawdowns (max DD overlap)
- **c)** Full: a) + b) + heatmap exportable (JSON/CSV), dendrogramme de clustering

### Q4: Portfolio Builder — niveau ?

- **a)** ✅ Simple allocation: pondération égale entre N stratégies
- **b)** ✅ Mean-variance: optimisation de Markowitz (efficient frontier),
       min variance, max Sharpe
- **c)** Full: b) + Black-Litterman, rééquilibrage périodique, contraintes
       sectorielles

### Q5: Rapports HTML — format ?

- **a)** ✅ Summary: metrics clés, equity curve chart, trade distribution
- **b)** ✅ StrategyQuant-style: multiple onglets avec overview, trades, equity,
       monthly/drawdown analysis, Monte Carlo overlay
- **c)** Full: b) + export PDF, dashboard intégrable (iframe)

### Q6: Architecture des données — format intermédiaire ?

- **a)** ✅ In-memory: tout dans des POJO/records (aucune persistance)
- **b)** SQLite: stockage des résultats de runs pour comparaison historique
- **c)** Full: b) + export JSON des configurations de run pour reproductibilité

### Q7: Priorité des features ?

- **a)** Metrics + Monte Carlo d'abord (fondations numériques)
- **b)** ✅ Metrics + Walk-Forward d'abord (fondations + optimisation)
- **c)** Parallèle: toutes les features en même temps

### Q8: Tests et validation ?

- **a)** Tests unitaires pour chaque nouvelle classe
- **b)** ✅ Tests unitaires + test d'intégration Monte Carlo (1 run de vérification)
- **c)** Full: a) + b) + fixtures de données de marché pour reproductibilité

### Q9: Interface utilisateur ?

- **a)** Console uniquement (printSummary / CSV export)
- **b)** ✅ Rapports HTML auto-générés
- **c)** Full: b) + API REST pour lancer des runs depuis le dashboard

---

## 🔷 Décisions Prises

Basé sur les réponses ci-dessus (marquées ✅) :

| # | Feature | Scope choisi |
|---|---------|-------------|
| 1 | Monte Carlo | Simple (a): randomisation ordre, 1000 runs, distribution |
| 2 | Walk-Forward | Multiple (b): fenêtres glissantes, moyenne OOS |
| 3 | Correlation | Standard (b): P&L + drawdown correlation |
| 4 | Portfolio | Mean-Variance (b): Markowitz, efficient frontier |
| 5 | HTML Reports | StrategyQuant-style (b): onglets multiples |
| 6 | Architecture | In-memory (a): pas de persistance dans ce sprint |
| 7 | Priorité | Parallèle metrics + WF (b) |
| 8 | Tests | Tests unitaires + intégration (b) |
| 9 | UI | Rapports HTML (b) |

---

## 📋 Sprint Backlog

### Epic 6: Advanced Backtesting & Portfolio Analytics

#### Story 6.1: Advanced Performance Metrics
Améliorer `BacktestResult` avec Sharpe, Sortino, Profit Factor, Calmar,
et intégrer commission/slippage dans `BacktestEngine`.

**Status:** 🎯 This sprint
**Priority:** P0

#### Story 6.2: Monte Carlo Simulation
Créer `MonteCarloSimulation` : randomisation des trades, runs parallèles,
distribution statistics (P&L, drawdown, Sharpe), percentiles reporting.

**Status:** 🎯 This sprint
**Priority:** P1

#### Story 6.3: Walk-Forward Optimization
Créer `WalkForwardOptimizer` : fenêtres IS/OOS glissantes, optimisation de paramètres,
validation cross-validation style, moyenne des résultats OOS.

**Status:** 🎯 This sprint
**Priority:** P1

#### Story 6.4: Correlation Matrix
Créer `CorrelationMatrix` : calcul de corrélation P&L et drawdown entre stratégies,
export JSON/CSV, heatmap data.

**Status:** 🎯 This sprint
**Priority:** P2

#### Story 6.5: Portfolio Builder
Créer `PortfolioBuilder` : allocation mean-variance, efficient frontier,
calcul de Sharpe portfolio, re-balancing.

**Status:** 🎯 This sprint
**Priority:** P2

#### Story 6.6: StrategyQuant-Style HTML Reports
Créer un générateur de rapport HTML multi-onglets avec graphiques (Chart.js),
équity curve, trades, monthly analysis, Monte Carlo overlay.

**Status:** 🎯 This sprint
**Priority:** P2

---

## ✅ Definition of Done

- [ ] Toutes les classes implémentées dans `trading-backtest`
- [ ] `mvn compile` passe sans erreur
- [ ] `mvn test` passe (nouveaux tests unitaires JUnit 5)
- [ ] `BacktestResult` inclut Sharpe, Sortino, Profit Factor, Calmar
- [ ] `BacktestEngine` supporte commission + slippage configurables
- [ ] `MonteCarloSimulation` produit des statistiques de distribution
- [ ] `WalkForwardOptimizer` produit des métriques IS/OOS
- [ ] `CorrelationMatrix` calcule P&L et drawdown corrélation
- [ ] `PortfolioBuilder` produit efficient frontier
- [ ] Rapport HTML généré avec Chart.js

---

## 📐 Architecture

```
trading-backtest/
├── BacktestEngine.java          ← amélioré: commission + slippage
├── BacktestResult.java          ← amélioré: Sharpe, Sortino, etc.
├── MonteCarloSimulation.java    ← NOUVEAU
├── WalkForwardOptimizer.java    ← NOUVEAU
├── CorrelationMatrix.java       ← NOUVEAU
├── PortfolioBuilder.java        ← NOUVEAU
├── PerformanceMetrics.java      ← NOUVEAU (utilitaires de calcul)
└── report/
    └── HtmlReportGenerator.java ← NOUVEAU
```

---

## ⚡ Dépendances

| Feature | Dépend du module | Dépend de la classe |
|---------|-----------------|---------------------|
| PerformanceMetrics | trading-core | Bar, Trade (for returns) |
| MonteCarloSimulation | trading-backtest | BacktestEngine, BacktestResult |
| WalkForwardOptimizer | trading-backtest | BacktestEngine, BacktestResult |
| CorrelationMatrix | trading-backtest | BacktestResult |
| PortfolioBuilder | trading-backtest | BacktestResult, CorrelationMatrix |
| HtmlReportGenerator | trading-backtest | BacktestResult, MonteCarloSimulation |

---

## 🧪 Stratégie de Test

- `PerformanceMetricsTest` — vérifie chaque métrique avec des données connues
- `MonteCarloSimulationTest` — vérifie nombre de runs, distributions valides
- `WalkForwardOptimizerTest` — vérifie fenêtres IS/OOS, pas de lookahead
- `CorrelationMatrixTest` — vérifie valeurs de corrélation connues
- `PortfolioBuilderTest` — vérifie efficient frontier, min variance
- `HtmlReportGeneratorTest` — vérifie que le HTML est généré sans erreur
