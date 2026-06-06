# Epics & Stories — Trading Bridge Desktop (Electron)

> Phase 3 — Solutioning
> Projet : Trading Bridge — Backtest Runner Desktop
> Stack : Electron + Vue 3 + Vite + TypeScript + TradingView Lightweight Charts

---

## Epic 1 — Fondation Java (endpoints manquants)

**Objectif** : Exposer l'equity curve, les trades, et les métriques manquantes via l'API REST du control plane.

### User Stories

| # | En tant que... | Je veux... | Valeur |
|---|---|---|---|
| US-1 | Trader | Que l'API me renvoie le Sharpe, PF, Win Rate après un backtest | Voir les métriques clés dans l'app |
| US-2 | Trader | Pouvoir récupérer la liste des trades d'un run | Les afficher dans le trade table |
| US-3 | Trader | Pouvoir récupérer l'equity curve d'un run | Tracer le graphique |

### Coding Stories

| # | Story | Effort | Description | Dépend de |
|---|-------|--------|-------------|-----------|
| 1.1 | Enrichir endedPayload | XS | Ajouter `sharpeRatio`, `profitFactor`, `winRatePct`, `totalCommission`, `totalSlippage` dans le map de `RunManager.executeRun()` | — |
| 1.2 | Endpoint trades | XS | Nouveau `GET /api/runs/{runId}/trades` → JSON array des trades (depuis `RunLauncher.create` / BacktestResult) | 1.1 |
| 1.3 | Endpoint equity-curve | XS | Nouveau `GET /api/runs/{runId}/equity-curve` → JSON array échantillonné (max 500 points) | 1.1 |
| 1.4 | Utilitaire sampleEquityCurve | XS | Downsample une equity curve de N points à max 500 (moyenne par intervalle) | — |

**Total Epic 1** : ~0.5 jour

---

## Epic 2 — Scaffold Electron + Vue 3

**Objectif** : Mettre en place le projet Electron avec Vue 3, Vite, TypeScript, et la structure de base.

### User Stories

| # | En tant que... | Je veux... | Valeur |
|---|---|---|---|
| US-4 | Développeur | Avoir un projet Electron qui compile et se lance | Base de travail |
| US-5 | Développeur | Avoir la communication main/renderer via IPC | Appeler des APIs systèmes |

### Coding Stories

| # | Story | Effort | Description | Dépend de |
|---|-------|--------|-------------|-----------|
| 2.1 | Init projet npm + Vite + Vue 3 | S | `npm create vue@latest`, config Vite, vue-router, TypeScript strict | — |
| 2.2 | Config Electron main process | S | `electron/main.ts`, `preload.ts` avec contextBridge, `electron-builder.yml` | 2.1 |
| 2.3 | Config Vite pour Electron | S | Plugin `vite-plugin-electron`, hot reload dev, build pour prod | 2.2 |
| 2.4 | Router + layout app | S | 4 routes (Dashboard, Results, Strategies, Compare), sidebar navigation | 2.3 |
| 2.5 | Types control-plane | XS | Fichier `types/control-plane.ts` avec toutes les interfaces | — |
| 2.6 | Composant Loading/Error states | XS | Wrapper pour afficher loading spinner + erreur + retry | 2.4 |

**Total Epic 2** : ~1 jour

---

## Epic 3 — API Client + WebSocket

**Objectif** : Client REST typé + WebSocket pour communiquer avec le Java control plane.

### User Stories

| # | En tant que... | Je veux... | Valeur |
|---|---|---|---|
| US-6 | Trader | Que l'app se connecte automatiquement au control plane | Pas de config manuelle |

### Coding Stories

| # | Story | Effort | Description | Dépend de |
|---|-------|--------|-------------|-----------|
| 3.1 | Composable useControlPlane | S | Client REST Axios/Fetch typé : `startRun()`, `getRun()`, `getStrategies()`, `getTrades()`, `getEquityCurve()` | 2.5 |
| 3.2 | Composable useRunWebSocket | M | Client WebSocket avec auto-reconnect, typage des events, buffer de replay | 2.5 |
| 3.3 | Config control plane URL | XS | URL par défaut `http://localhost:8080`, overridable via env ou settings | 3.1 |

**Total Epic 3** : ~0.5 jour

---

## Epic 4 — Dashboard (Backtest Runner)

**Objectif** : Page principale pour sélectionner une stratégie, configurer et lancer un backtest.

### User Stories

| # | En tant que... | Je veux... | Valeur |
|---|---|---|---|
| US-1 | Trader | Sélectionner une stratégie, une paire, une période et lancer un backtest | Éviter la CLI |
| US-6 | Trader | Changer les paramètres de coût avant de lancer | Ajuster le réalisme |

### Coding Stories

| # | Story | Effort | Description | Dépend de |
|---|-------|--------|-------------|-----------|
| 4.1 | BacktestForm component | M | Dropdown stratégie (recherchable), select paire, select année, inputs capital/commission/slippage, bouton RUN + validation | 3.1, 3.2 |
| 4.2 | Dashboard view layout | S | Formulaire en haut, section résultat en bas (état vide/loading/result) | 4.1 |

**Total Epic 4** : ~1 jour

---

## Epic 5 — Strategy Catalog

**Objectif** : Page pour parcourir les 55+ stratégies avec recherche et filtres.

### User Stories

| # | En tant que... | Je veux... | Valeur |
|---|---|---|---|
| US-9 | Trader | Voir le strategy catalog avec les déploiements actifs | Savoir ce qui tourne |

### Coding Stories

| # | Story | Effort | Description | Dépend de |
|---|-------|--------|-------------|-----------|
| 5.1 | StrategyCard component | S | Carte avec nom, famille, paire, badge déploiement (coloré), clic → navigue vers Dashboard | — |
| 5.2 | StrategiesView | M | Grille responsive de StrategyCard, barre de recherche, filtre par famille, filtre par déploiement | 5.1, 3.1 |
| 5.3 | Navigation depuis carte | XS | Click sur une carte → DashboardView pré-remplie avec cette stratégie | 5.2 |

**Total Epic 5** : ~0.5 jour

---

## Epic 6 — Results & Charts

**Objectif** : Visualisation complète des résultats de backtest avec TradingView Lightweight Charts.

### User Stories

| # | En tant que... | Je veux... | Valeur |
|---|---|---|---|
| US-2 | Trader | Voir l'equity curve se tracer | Valider visuellement |
| US-3 | Trader | Voir les métriques clés en un coup d'œil | Décision rapide |
| US-4 | Trader | Explorer la liste des trades avec filtres | Analyse détaillée |
| US-5 | Trader | Voir les monthly returns | Patterns saisonniers |
| US-7 | Trader | Exporter en PDF ou CSV | Partager |

### Coding Stories

| # | Story | Effort | Description | Dépend de |
|---|-------|--------|-------------|-----------|
| 6.1 | CandlestickChart component | M | TradingView CandlestickSeries (bars) + markers (trades) + volume histogram. Props: `bars: Bar[]`, `trades: Trade[]` | 3.1, 2.4 |
| 6.2 | EquityChart component | S | TradingView LineSeries + area fill | 3.1 |
| 6.3 | KpiStrip component | XS | Grille 2×4 de tuiles métriques avec couleurs | — |
| 6.4 | TradeTable component | S | Tableau triable/filtrable, formatage P&L coloré | — |
| 6.5 | MonthlyReturns component | S | Histogramme mensuel (TradingView HistogramSeries) | — |
| 6.6 | ResultsView layout | M | Assemble KpiStrip + CandlestickChart + EquityChart + MonthlyReturns + TradeTable dans une page scrollable. 2 sections : "Overview" (charts) et "Trades" (tableau) | 6.1-6.5 |
| 6.7 | Export CSV | XS | Générer un CSV depuis la trade list | 6.4 |
| 6.8 | Export HTML/PDF | XS | Ouvrir le rapport HTML du control plane dans le navigateur système | 3.1 |

**Total Epic 6** : ~1.5 jours

---

## Epic 7 — Compare Runs

**Objectif** : Comparer plusieurs runs backtest côte à côte.

### User Stories

| # | En tant que... | Je veux... | Valeur |
|---|---|---|---|
| US-8 | Trader | Comparer plusieurs backtests | Qualifier les stratégies |

### Coding Stories

| # | Story | Effort | Description | Dépend de |
|---|-------|--------|-------------|-----------|
| 7.1 | CompareView | M | Liste des runs récents, cases à cocher, tableau comparatif (2+ runs sélectionnés), equity curves superposées | 6.2, 3.1 |

**Total Epic 7** : ~0.5 jour

---

## Epic 8 — Packaging & CI

**Objectif** : Builder, packager l'app Electron et mettre en place la CI.

### Coding Stories

| # | Story | Effort | Description | Dépend de |
|---|-------|--------|-------------|-----------|
| 8.1 | Electron builder config | S | Config `electron-builder.yml` pour Linux (.AppImage/.deb) + macOS (.dmg) | 2.2 |
| 8.2 | GitHub Actions CI | S | Workflow : lint (tsc, eslint), build, test, package (artifact upload) | 8.1 |
| 8.3 | README desktop | XS | Documentation : build, dev, configuration control plane URL | — |

**Total Epic 8** : ~0.5 jour

---

## Build Order (ordre d'implémentation)

| Phase | Epics | Dépend de | Durée estimée |
|-------|-------|-----------|---------------|
| **4.1** | Epic 1 — Java endpoints | — | 0.5j |
| **4.2** | Epic 2 — Scaffold Electron | — | 1j |
| **4.3** | Epic 3 — API Client | Epic 2 | 0.5j |
| **4.4** | Epic 4 — Dashboard | Epic 3 | 1j |
| **4.5** | Epic 5 — Strategy Catalog | Epic 3 | 0.5j |
| **4.6** | Epic 6 — Results & Charts | Epic 3, Epic 4 | 1.5j |
| **4.7** | Epic 7 — Compare Runs | Epic 6 | 0.5j |
| **4.8** | Epic 8 — Packaging & CI | Epic 2, Epic 4-6 | 0.5j |

**Total estimé** : ~6 jours
