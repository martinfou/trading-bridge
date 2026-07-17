---
stepsCompleted:
  - "step-01-validate-prerequisites.md"
  - "step-02-design-epics.md"
  - "step-03-create-stories.md"
inputDocuments:
  - "User Prompt (Problèmes Identifiés — Trading Bridge)"
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
FR9: Développer un outil d'allocation de portefeuille automatisé qui gère la création de **multiples portefeuilles** distincts, en sélectionnant et équilibrant dynamiquement les stratégies en fonction de leur absence de corrélation.

### NonFunctional Requirements

NFR1: Documenter comme contrainte d'architecture que le framework est H1 uniquement et ne supporte pas tick / M5 / M15.

### Additional Requirements

- Les frais de backtest (commission/slippage) doivent impacter les résultats avec une échelle de 20-30% des profits.

### UX Design Requirements

Aucun (Projet backend).

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


