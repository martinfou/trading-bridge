---
stepsCompleted:
  - "step-01-validate-prerequisites.md"
  - "step-02-design-epics.md"
  - "step-03-create-stories.md"
inputDocuments:
  - "User Prompt (Strategy PnL & Trade Ledger Dashboard for Trading Bridge Desktop)"
  - "docs/architecture-trading-bridge-desktop.md"
  - "docs/api-contracts-trading-bridge-dashboard.md"
  - "docs/component-inventory-trading-bridge-desktop.md"
---

# Trading Bridge - Epic Breakdown

## Overview

This document provides the complete epic and story breakdown for Trading Bridge, decomposing the requirements from the PRD, UX Design if it exists, and Architecture requirements into implementable stories.

## Requirements Inventory

### Functional Requirements

FR1: Valider manuellement 10 trades sur `LtRSI3Momentum.java` pour écarter un look-ahead bias (RSI calculé sur la barre courante au lieu de la précédente).
FR2: Brancher `BacktestExecutionCost.java` dans le moteur de backtest pour simuler les frais réels per-trade (basé sur le modèle de spread pur d'OANDA, sans commission fixe).
FR3: Créer 1-2 stratégies mean-reversion pures pour hedger le portefeuille face aux marchés en range.
FR4: Imposer une diversification des indicateurs pour les futures stratégies générées (ne plus utiliser que le RSI).
FR5: Imposer une diversification des actifs en forçant 50% des nouvelles stratégies sur USD/JPY, GBP/USD, AUD/USD, USD/CAD.
FR6: Valider systématiquement out-of-sample (2018-2021) toute stratégie avant de l'ajouter au catalogue live (éviter le data snooping).
FR7: Mettre à jour les données historiques jusqu'à la date courante en exécutant `./scripts/download-data.sh`.
FR8: Restreindre l'implémentation Java des stratégies "NewsWeekly" uniquement si le concept peut être transformé en stratégie permanente.
FR9: Développer un outil d'allocation de portefeuille automatisé qui gère la création de **multiples portefeuilles** distincts, en sélectionnant et équilibrant dynamiquement les stratégies en fonction de leur absence de correction.
FR10: Visualiser un bandeau récapitulatif des KPI de portefeuille (PnL Réalisé total, PnL Non-réalisé, Taux de réussite global, Profit Factor, Max Drawdown).
FR11: Afficher les cartes de performance et statistiques détaillées par stratégie (Ratio de Sharpe, nombre de trades, PnL journalier, exposition active et statut LIVE/STALE).
FR12: Présenter un registre unifié des trades historiques et temps réel (ID stratégie, ticket ID, symbole, sens ACHAT/VENTE, horodatage UTC, prix d'exécution, quantité, PnL net, frais de spread/commission).
FR13: Permettre le filtrage dynamique du registre des trades par nom de stratégie, symbole d'actif, sens de trade, plage de dates et résultat (gagnant/perdant).
FR14: Proposer un graphique chronologique interactif affichant les courbes de capital cumulées du portefeuille et les trajectoires de PnL individuelles par stratégie.
FR15: Permettre l'exportation des enregistrements de trades filtrés au format CSV ou JSON depuis l'interface utilisateur.

### NonFunctional Requirements

NFR1: Documenter comme contrainte d'architecture que le framework est H1 uniquement et ne supporte pas tick / M5 / M15.
NFR2: Le composant de registre des trades doit supporter un défilement virtuel fluide et un filtrage rapide pour jusqu'à 20 000 enregistrements sans figer le thread UI (<150ms).
NFR3: Tous les horodatages des trades doivent être traités en UTC Instant ISO-8601 avec une option d'affichage en heure locale (`America/Toronto`).

### Additional Requirements

- Les frais de backtest (commission/slippage) doivent impacter les résultats avec une échelle de 20-30% des profits.
- AR1: Le serveur Java (`trading-runtime`) doit exposer les points de terminaison REST `/api/v1/trades` et `/api/v1/pnl/summary`.
- AR2: L'application Electron / Vue 3 (`desktop/`) doit intégrer le composable `useControlPlane.ts` pour consommer l'API REST et le flux WebSocket.

### UX Design Requirements

UX-DR1: Thème sombre quant sous Tailwind CSS aligné avec `LiveTradingView.vue`.
UX-DR2: Tableau de données réactif avec en-têtes fixes, icônes Lucide et volet d'inspection des détails de chaque trade.

### FR Coverage Map

- **FR1:** Epic 1 - Validation manuelle du look-ahead bias
- **FR2:** Epic 1 - Simulation des frais d'exécution (modèle OANDA spread)
- **FR3:** Epic 3 - Création de stratégies mean-reversion
- **FR4:** Epic 4 - Diversification des indicateurs via l'allocateur
- **FR5:** Epic 4 - Diversification des actifs via l'allocateur
- **FR6:** Epic 2 - Validation systématique out-of-sample
- **FR7:** Epic 1 - Mise à jour des données (download-data.sh)
- **FR8:** Epic 2 - Restriction des stratégies NewsWeekly
- **FR9:** Epic 4 - Outil d'allocation de portefeuilles multiples non corrélés
- **FR10:** Epic 5 - Bandeau récapitulatif des KPI de portefeuille
- **FR11:** Epic 5 - Cartes de performance par stratégie
- **FR12:** Epic 5 - Registre unifié des trades
- **FR13:** Epic 5 - Filtrage dynamique des trades
- **FR14:** Epic 5 - Graphique PnL & Equity multi-stratégies
- **FR15:** Epic 5 - Exportation des trades CSV/JSON

## Epic List

### Epic 1: Backtest Reliability & Data Foundation
S'assurer que les résultats de backtest reflètent parfaitement la réalité du marché (frais d'exécution OANDA, pas de look-ahead, données à jour) afin de prendre des décisions basées sur des chiffres fiables.
**FRs covered:** FR1, FR2, FR7

### Epic 2: Out-of-Sample Strategy Qualification Framework
Empêcher l'overfitting (data snooping) et filtrer les stratégies jetables pour garantir que seules les stratégies robustes et durables soient éligibles au trading réel.
**FRs covered:** FR6, FR8

### Epic 3: Mean-Reversion Strategy Expansion
Fournir de nouvelles stratégies de "hedging" (market ranging) pour alimenter l'allocateur de portefeuille et protéger le capital lors des périodes où le Trend Following échoue.
**FRs covered:** FR3

### Epic 4: Automated Multi-Portfolio Allocator
Permettre aux utilisateurs de générer automatiquement des portefeuilles distincts, diversifiés et non corrélés, remplaçant les règles arbitraires par une allocation mathématique (par actifs, indicateurs et faible corrélation).
**FRs covered:** FR4, FR5, FR9

### Epic 5: Strategy PnL & Trade Ledger Desktop Dashboard
Fournir aux quants et opérateurs de stratégies un tableau de bord visuel centralisé et temps réel au sein de l'application de bureau Trading Bridge Desktop (`desktop/`) pour suivre les KPI de portefeuille, les performances par stratégie, le graphique interactif PnL et le registre des trades filtrable et exportable.
**FRs covered:** FR10, FR11, FR12, FR13, FR14, FR15

## Epic 1: Backtest Reliability & Data Foundation

S'assurer que les résultats de backtest reflètent parfaitement la réalité du marché (frais d'exécution OANDA, pas de look-ahead, données à jour) afin de prendre des décisions basées sur des chiffres fiables.

### Story 1.1: Update Historical Data to Present

As a quant researcher,
I want to download the latest market data (post-May 2026),
So that my backtests and OOS validation are run against current market conditions.

**Acceptance Criteria:**

**Given** the `scripts/download-data.sh` is configured
**When** the script is executed
**Then** it successfully downloads missing `.bars` data up to the current date
**And** the `BacktestEngine` can successfully read and process the newly downloaded bars.

### Story 1.2: Implement OANDA Spread Execution Cost

As a quantitative developer,
I want the backtest engine to deduct realistic spread costs per trade,
So that my backtest PnL accurately reflects OANDA's zero-commission, spread-only fee model.

**Acceptance Criteria:**

**Given** a strategy running in the `BacktestEngine`
**When** an order (MARKET, LIMIT, or STOP) is filled
**Then** the `BacktestExecutionCost` logic deducts the appropriate spread distance from the entry/exit price
**And** the final PnL correctly reflects this drag without applying a flat commission fee.

### Story 1.3: Verify LtRSI3Momentum Trade Integrity

As a quantitative developer,
I want to manually audit 10 trades generated by `LtRSI3Momentum`,
So that I can definitively rule out any look-ahead bias in the RSI calculation.

**Acceptance Criteria:**

**Given** the `LtRSI3Momentum` strategy is running over historical data
**When** a trade signal is generated
**Then** the manual logs confirm that the RSI value used was calculated purely on closed bars
**And** no future data was accessed before the current bar closed.

## Epic 2: Out-of-Sample Strategy Qualification Framework

Empêcher l'overfitting (data snooping) et filtrer les stratégies jetables pour garantir que seules les stratégies robustes et durables soient éligibles au trading réel.

### Story 2.1: Automated Out-of-Sample (OOS) Validation Gate

As a quantitative researcher,
I want the backtest runner to automatically test strategies against an unseen OOS period (2018-2021) after successful in-sample testing,
So that I can objectively reject overfit strategies before they go live.

**Acceptance Criteria:**

**Given** the `RunAllBatchBacktests` pipeline is executing
**When** a strategy passes the initial 2024-2026 testing phase with a positive expectancy
**Then** the runner automatically executes a second backtest purely on the 2018-2021 dataset
**And** only outputs the strategy to the `qualified_strategies` list if the OOS Sharpe Ratio remains positive.

### Story 2.2: Enforce Permanent Strategy Architectures

As a system architect,
I want to enforce a structural requirement that prevents disposable "NewsWeekly" code from entering the live catalog,
So that technical debt doesn't accumulate from temporary, unmaintainable hacks.

**Acceptance Criteria:**

**Given** a developer is implementing a new strategy inspired by a weekly news event
**When** the strategy is loaded into `PropStrategyCatalog`
**Then** it must extend an explicitly defined `DurableStrategy` interface or base class that guarantees it operates independently of hardcoded dates or disposable logic
**And** any strategy relying on hardcoded event timestamps fails to initialize.

## Epic 3: Mean-Reversion Strategy Expansion

Fournir de nouvelles stratégies de "hedging" (market ranging) pour alimenter l'allocateur de portefeuille et protéger le capital lors des périodes où le Trend Following échoue.

### Story 3.1: Develop Bollinger Band Fade Strategy

As a quantitative developer,
I want to implement a mean-reversion strategy based on fading Bollinger Band breakouts,
So that the portfolio has a profitable edge during ranging, low-volatility market conditions.

**Acceptance Criteria:**

**Given** the `PropStrategyCatalog` is initialized
**When** the `BollingerFadeStrategy` is evaluated on a ranging market
**Then** it successfully triggers short signals on upper band touches and long signals on lower band touches (with appropriate mean-reversion stop losses)
**And** it passes the OOS validation gate implemented in Epic 2.

### Story 3.2: Develop Stochastic Range Oscillator Strategy

As a quantitative developer,
I want to implement a second mean-reversion strategy using the Stochastic Oscillator,
So that we have multiple uncorrelated mean-reversion options to hedge against trend-following drawdowns.

**Acceptance Criteria:**

**Given** the `PropStrategyCatalog` is initialized
**When** the `StochasticRangeStrategy` detects extreme overbought (>80) or oversold (<20) conditions with a crossover
**Then** it executes counter-trend trades expecting a return to the midpoint
**And** it demonstrates a negative correlation to our existing moving-average trend strategies.

## Epic 4: Dual-Mode Multi-Portfolio Allocator

Permettre aux utilisateurs de générer automatiquement des portefeuilles via des profils de risque, ou de les construire manuellement via une interface (TUI/GUI) avec de simples avertissements en cas de mauvaise diversification.

### Story 4.1: Develop Correlation & Constraints Engine

As a system architect,
I want the engine to calculate a correlation matrix and expose strategy metadata (asset class, indicator),
So that downstream user interfaces (TUI/GUI) have the mathematical data needed to build and validate portfolios.

**Acceptance Criteria:**

**Given** the OOS qualified strategies
**When** the Correlation Engine is queried
**Then** it returns the pairwise correlation matrix and the metadata tags (Asset/Indicator) for each strategy
**And** it provides an API endpoint/method capable of flagging high-correlation pairs (>0.6).

### Story 4.2: Build Automated Risk-Profile Allocator

As a standard user,
I want to select a predefined risk profile (e.g., Aggressive, Balanced, Conservative),
So that the system automatically generates an optimal, uncorrelated portfolio matching that profile.

**Acceptance Criteria:**

**Given** the Portfolio Allocator is run in automated mode
**When** the user selects "Balanced"
**Then** the algorithm outputs a portfolio that strictly respects the <50% asset/indicator limits
**And** minimizes correlation using the engine from 4.1.

### Story 4.3: Build Interactive Manual Allocator (TUI/GUI backend)

As an advanced user,
I want an interface to manually construct my portfolio strategy by strategy,
So that I have full control over the exact weights and components being deployed.

**Acceptance Criteria:**

**Given** the user is in interactive manual mode
**When** they select a combination of strategies that violates FR4 (indicator) or FR5 (asset class) constraints
**Then** the interface issues a clear warning explaining the risk
**And** allows the user to override the warning and save the portfolio anyway (soft constraint).

### Story 4.4: Export GUI-Ready Portfolio JSON Schema

As a frontend developer,
I want the allocator to export a standardized `portfolio_export.json` file,
So that the future GUI/TUI can easily render visual representations (donut charts, equity curves, correlation matrix).

**Acceptance Criteria:**

**Given** a portfolio has been successfully generated (via automated or manual mode)
**When** the save process completes
**Then** it outputs a JSON file containing the combined equity curve array
**And** the asset allocation distribution weights
**And** the pairwise correlation matrix of the selected strategies.

## Epic 5: Strategy PnL & Trade Ledger Desktop Dashboard

Fournir aux quants et opérateurs de stratégies un tableau de bord visuel centralisé et temps réel au sein de l'application de bureau Trading Bridge Desktop (`desktop/`) pour suivre les KPI de portefeuille, les performances par stratégie, le graphique interactif PnL et le registre des trades filtrable et exportable.

### Story 5.1: Expose Java Control Plane Trade Ledger & PnL REST API

As a frontend desktop developer,
I want the Java `trading-runtime` control plane to expose REST endpoints `/api/v1/trades` and `/api/v1/pnl/summary`,
So that the desktop app can query historical and live trade logs and aggregated PnL metrics.

**Acceptance Criteria:**

**Given** the Java `trading-runtime` server is active on `localhost:8080`
**When** the desktop app issues a `GET /api/v1/trades` request with optional parameters (`strategyId`, `symbol`, `side`, `limit`, `offset`)
**Then** the endpoint returns a JSON payload of trade execution records matching the filter criteria with HTTP 200 OK
**And** `GET /api/v1/pnl/summary` returns the portfolio-level and per-strategy PnL metrics (Realized PnL, Unrealized PnL, Win Rate, Profit Factor, Max Drawdown).

### Story 5.2: Build Desktop Portfolio KPI Strip & Strategy Performance Cards

As a quant strategy operator,
I want to view a top-level KPI banner and per-strategy cards in Trading Bridge Desktop,
So that I can monitor portfolio health and individual strategy performance at a glance.

**Acceptance Criteria:**

**Given** the user is viewing the Desktop Dashboard view
**When** trade data is loaded from the control plane API
**Then** the KPI strip displays Total Realized PnL, Unrealized PnL, Win Rate %, Profit Factor, and Max Drawdown with color-coded badges
**And** each strategy card displays its Sharpe Ratio, trade count, daily PnL, active position count, and LIVE/STALE liveness indicator.

### Story 5.3: Build Interactive Unified Trade Ledger Table Component with Filtering & CSV Export

As a trader,
I want an interactive, filterable trade ledger table in Vue 3 with CSV export capabilities,
So that I can inspect, filter, and audit all trade executions across all strategies.

**Acceptance Criteria:**

**Given** the `TradeLedgerTable.vue` component is mounted
**When** the user applies filters for strategy name, asset symbol, side (BUY/SELL), or date range
**Then** the trade table instantly updates to show matching trade rows with sticky headers
**And** clicking the "Export CSV" button downloads a standard `.csv` file containing the filtered trade records.

### Story 5.4: Implement Multi-Strategy Equity & PnL Comparison Chart Component

As a quantitative researcher,
I want an interactive multi-strategy time-series chart component in Vue 3,
So that I can visually compare PnL trajectories and equity curves across different strategies over time.

**Acceptance Criteria:**

**Given** trade history and PnL time series data are present
**When** the user toggles individual strategy checkboxes on the chart legend
**Then** the multi-series line chart updates dynamically to render selected equity/PnL curves
**And** hovering over any point displays a tooltip with timestamp, strategy name, and cumulative PnL.

### Story 5.5: Integrate Strategy PnL Dashboard View in Trading Bridge Desktop App

As a desktop user,
I want a dedicated "Strategy PnL & Trade Ledger" navigation tab in Trading Bridge Desktop,
So that I can access the complete dashboard from the main navigation sidebar.

**Acceptance Criteria:**

**Given** the Electron desktop application is launched
**When** the user clicks on the "Strategy PnL" item in the sidebar navigation
**Then** Vue Router navigates to `/strategy-pnl` rendering `StrategyPnlDashboardView.vue`
**And** real-time WebSocket updates automatically refresh trade logs and PnL metrics as new orders fill.



