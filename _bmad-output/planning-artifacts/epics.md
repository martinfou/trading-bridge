# Trading Bridge — Epic Breakdown

> Generated from `docs/sprint-plan.md` for BMAD sprint tracking.
> Martin Fournier — May 2026

## Overview

Epics map to the five project sprints: foundation, XML parser, advanced backtest, broker connectors, and production hardening.

## Epic List

1. **Epic 1:** Foundation — Maven monorepo, domain models, basic backtest
2. **Epic 2:** StrategyQuant XML Parser — XML/JForex to Java
3. **Epic 3:** Advanced Backtest — Realistic simulation and reporting
4. **Epic 4:** Broker Connectors — OANDA and Interactive Brokers live execution
5. **Epic 5:** Production — Persistence, monitoring, tests, deployment

---

## Epic 1: Foundation

Establish the Maven monorepo, core domain models, Strategy interface, basic BacktestEngine, CSV data loading, and a working SMA crossover example.

### Story 1.1: Maven Monorepo Structure

As a developer,
I want a multi-module Maven parent POM with core modules declared,
So that the project builds as a cohesive trading platform.

**Acceptance Criteria:**

**Given** the repository root
**When** I run `mvn clean install`
**Then** parent and child modules compile successfully
**And** modules include trading-core, trading-backtest, trading-parser, trading-broker, trading-data, trading-strategies, trading-examples

### Story 1.2: Domain Models

As a developer,
I want Bar, Order, Position, and Trade models in trading-core,
So that strategies and engines share a consistent domain language.

**Acceptance Criteria:**

**Given** trading-core is built
**When** I inspect `com.martinfou.trading.core`
**Then** Bar, Order, Position, and Trade exist with documented fields per specs

### Story 1.3: Strategy Interface

As a strategy author,
I want a Strategy interface with onBar, onTick, getPendingOrders, and reset,
So that backtest and live engines can drive any strategy uniformly.

**Acceptance Criteria:**

**Given** the Strategy interface
**When** a class implements it
**Then** the engine can call lifecycle methods without JForex dependencies

### Story 1.4: Basic Backtest Engine

As a quant developer,
I want BacktestEngine to simulate bar-by-bar execution with market orders,
So that I can validate strategies on historical data.

**Acceptance Criteria:**

**Given** a Strategy and List<Bar>
**When** I call `run()`
**Then** BacktestResult includes equity curve, trade count, and PnL summary

### Story 1.5: CSV Data Loader

As a developer,
I want DataLoader to import standard OHLCV and StrategyQuant CSV formats,
So that backtests use real or exported historical data.

**Acceptance Criteria:**

**Given** a valid CSV path and symbol
**When** I load via `loadCSV` or `loadStrategyQuantCSV`
**Then** a List<Bar> is returned with parsed timestamps and OHLCV

### Story 1.6: SMA Crossover Example

As a developer,
I want a runnable SMA crossover strategy and launcher,
So that the end-to-end backtest path is demonstrated.

**Acceptance Criteria:**

**Given** sample or random bar data
**When** I run RunBacktest with SmaCrossoverStrategy
**Then** the backtest completes and prints a summary

### Story 1.7: Foundation Documentation

As a project owner,
I want README, specs, sprint plan, and conversion guide in docs/,
So that humans and AI agents understand architecture and JForex mapping.

**Acceptance Criteria:**

**Given** the docs/ folder
**When** I review project documentation
**Then** specs, sprint-plan, conversion-guide, and README exist and describe Sprint 1 deliverables

---

## Epic 2: StrategyQuant XML Parser

Convert StrategyQuant XML strategies into Java that implements Strategy and runs in BacktestEngine.

### Story 2.1: Analyze StrategyQuant XML Format

As a parser developer,
I want documented XML structure and sample files analyzed,
So that SqXmlParser targets the correct schema elements.

**Acceptance Criteria:**

**Given** representative StrategyQuant XML samples
**When** analysis is complete
**Then** element mapping to StrategyConfig is documented in specs or parser module

### Story 2.2: SqXmlParser Implementation

As a developer,
I want SqXmlParser in trading-parser using Jackson XML or JAXB,
So that strategy XML files parse into Java objects.

**Acceptance Criteria:**

**Given** a valid StrategyQuant XML file
**When** parsed by SqXmlParser
**Then** a StrategyConfig instance is returned without errors

### Story 2.3: StrategyConfig POJO

As a developer,
I want a StrategyConfig model mirroring XML (indicators, rules, sizing),
So that parsed data is strongly typed and testable.

**Acceptance Criteria:**

**Given** parsed XML
**When** I access StrategyConfig
**Then** name, symbol, timeframe, indicators, entry/exit rules, and position sizing are available

### Story 2.4: Core Indicators SMA EMA RSI

As a strategy converter,
I want SMA, EMA, and RSI indicator support,
So that common StrategyQuant strategies can be evaluated.

**Acceptance Criteria:**

**Given** indicator definitions in XML
**When** a strategy requires SMA, EMA, or RSI
**Then** values are computed correctly on bar history

### Story 2.5: Extended Indicators MACD Bollinger ATR

As a strategy converter,
I want MACD, Bollinger Bands, and ATR support,
So that advanced StrategyQuant strategies are supported.

**Acceptance Criteria:**

**Given** P1 indicator definitions in XML
**When** a strategy uses MACD, Bollinger, or ATR
**Then** indicators compute and feed entry/exit logic

### Story 2.6: Entry Conditions

As a strategy converter,
I want entry rules (crossover, level, etc.) translated to order signals,
So that strategies open positions per XML logic.

**Acceptance Criteria:**

**Given** EntryRules in XML
**When** conditions are met on a bar
**Then** appropriate BUY/SELL orders are queued via getPendingOrders pattern

### Story 2.7: Exit Conditions

As a strategy converter,
I want exit rules including SL, TP, and trailing stops,
So that positions close per XML logic.

**Acceptance Criteria:**

**Given** ExitRules in XML
**When** stop or take profit conditions trigger
**Then** closing orders are generated consistent with conversion-guide mapping

### Story 2.8: Position Sizing

As a risk-aware developer,
I want fixed lot, risk percent, and martingale sizing from XML,
So that generated strategies respect position sizing configuration.

**Acceptance Criteria:**

**Given** PositionSizing in XML
**When** an entry signal fires
**Then** order quantity reflects the configured sizing method

### Story 2.9: Java Code Generator

As a developer,
I want generated Java Strategy classes from parsed XML,
So that output compiles and runs in BacktestEngine without manual rewrite.

**Acceptance Criteria:**

**Given** a parsed StrategyConfig
**When** code generation runs
**Then** generated Java compiles with `mvn compile`
**And** backtest on sample XML strategy produces coherent results

### Story 2.10: UTC Timezone Migration

As a developer,
I want all timestamps stored and compared in UTC with a documented display timezone,
So that backtest, live OANDA data, and economic calendar events align without silent offset bugs.

**Acceptance Criteria:**

**Given** the conventions in docs/specs.md §2.5
**When** timestamps flow through OANDA, CSV, calendar, and domain models
**Then** canonical representation is UTC (`Instant` or explicit UTC conversion)
**And** display uses `America/Toronto` only at UI/log boundaries
**And** `mvn test` passes for affected modules

---

## Epic 3: Advanced Backtest

Realistic backtesting with commissions, slippage, full trade lifecycle, risk controls, and reporting.

### Story 3.1: Complete Limit and Stop Order Simulation

As a quant developer,
I want LIMIT/STOP fills and open-position tracking integrated with SL/TP,
So that backtests reflect realistic order behavior.

**Acceptance Criteria:**

**Given** limit/stop orders with stopLoss and takeProfit
**When** BacktestEngine processes bars
**Then** fills and exits behave per specs Sprint 3

### Story 3.2: Commission and Slippage

As a quant developer,
I want configurable commission and slippage per trade,
So that net PnL reflects trading costs.

**Acceptance Criteria:**

**Given** commission and slippage settings
**When** a trade fills
**Then** costs are deducted from equity

### Story 3.3: Trade Exit Lifecycle

As a quant developer,
I want trades with explicit exit prices and reasons (TP, SL),
So that BacktestResult.trades are complete round-trips.

**Acceptance Criteria:**

**Given** an open position
**When** TP or SL triggers on a bar
**Then** Trade records include exit timestamp and price

### Story 3.4: Multi-Timeframe Support

As a strategy author,
I want multiple bar series (e.g. H1 + D1),
So that multi-timeframe strategies backtest correctly.

**Acceptance Criteria:**

**Given** two bar series for one symbol
**When** strategy references higher timeframe
**Then** indicators align bars without lookahead bias

### Story 3.5: Risk Management Rules

As a risk manager,
I want position size percent and daily loss limits in the engine,
So that backtests enforce portfolio constraints.

**Acceptance Criteria:**

**Given** risk configuration
**When** limits are breached
**Then** new entries are blocked until reset rules apply

### Story 3.6: HTML Backtest Report

As a user,
I want an HTML report with equity curve charts,
So that I can review backtest results visually.

**Acceptance Criteria:**

**Given** a completed backtest
**When** report is generated
**Then** HTML includes equity curve and key metrics

### Story 3.7: Trade CSV Export

As a user,
I want CSV export of all trades,
So that I can analyze results in spreadsheets.

**Acceptance Criteria:**

**Given** BacktestResult with trades
**When** export runs
**Then** a CSV file lists entry/exit, PnL, and duration

### Story 3.8: Advanced Performance Metrics

As a quant developer,
I want Sharpe, Profit Factor, and Calmar in BacktestResult,
So that strategy quality is measured beyond win rate.

**Acceptance Criteria:**

**Given** equity curve and trades
**When** metrics are calculated
**Then** Sharpe, profit factor, and Calmar are included in summary

---

## Epic 4: Broker Connectors

Live execution and market data via OANDA v20 and Interactive Brokers.

### Story 4.1: Broker Interface

As an integrator,
I want a common Broker interface in trading-broker,
So that OANDA and IBKR share connect, subscribe, and placeOrder contracts.

**Acceptance Criteria:**

**Given** trading-broker module
**When** I inspect Broker interface
**Then** connect, disconnect, subscribe, and placeOrder are defined per specs

### Story 4.2: OANDA v20 REST Broker

As a trader,
I want OandaBroker using v20 REST on practice account,
So that demo orders execute via API.

**Acceptance Criteria:**

**Given** valid OANDA practice credentials
**When** I place a market order
**Then** order is acknowledged by OANDA API

### Story 4.3: Interactive Brokers API

As a trader,
I want IbkrBroker via TWS or IB Gateway,
So that IBKR accounts can trade through the bridge.

**Acceptance Criteria:**

**Given** IB Gateway running
**When** broker connects
**Then** connection state is true and orders can be submitted

### Story 4.4: Real-Time Market Data

As a strategy runner,
I want tick and bar streaming from brokers,
So that onTick and onBar receive live data.

**Acceptance Criteria:**

**Given** an active subscription
**When** market data arrives
**Then** Strategy receives ticks or aggregated bars

### Story 4.5: Live Market Order Execution

As a trader,
I want MARKET orders routed to the active broker,
So that strategy signals execute live.

**Acceptance Criteria:**

**Given** a pending MARKET order from Strategy
**When** engine processes the signal
**Then** broker places the order and returns fill status

### Story 4.6: Live Limit and Stop Orders

As a trader,
I want LIMIT and STOP orders on live brokers,
So that advanced order types work outside backtest.

**Acceptance Criteria:**

**Given** LIMIT or STOP order from Strategy
**When** submitted to broker
**Then** order is tracked until fill or cancel

### Story 4.7: Position Synchronization

As a trader,
I want broker positions synced with internal state,
So that strategy logic matches account reality.

**Acceptance Criteria:**

**Given** open positions at broker
**When** sync runs
**Then** internal Position state matches broker holdings

### Story 4.8: Paper Trading Mode

As a developer,
I want a simulation mode that mimics live flow without real money,
So that strategies are validated before production.

**Acceptance Criteria:**

**Given** paper trading enabled
**When** orders are placed
**Then** fills are simulated with realistic latency rules

---

## Epic 5: Production

Operational readiness: persistence, logging, alerts, dashboard, tests, and documentation.

### Story 5.1: SQLite Trade Persistence

As an operator,
I want trades and equity curves stored in SQLite,
So that history survives restarts.

**Acceptance Criteria:**

**Given** completed trades from live or backtest
**When** persistence runs
**Then** records are queryable from SQLite

### Story 5.2: Structured Logging

As an operator,
I want rotating log files with structured fields,
So that production issues are diagnosable.

**Acceptance Criteria:**

**Given** application running
**When** events occur
**Then** logs write to files with rotation policy

### Story 5.3: Telegram Monitoring Alerts

As a trader,
I want Telegram notifications on errors and key events,
So that I am alerted without watching logs.

**Acceptance Criteria:**

**Given** Telegram bot configured
**When** configured alert conditions fire
**Then** a message is delivered to the user

### Story 5.4: Web Dashboard

As a user,
I want a lightweight web dashboard for status and metrics,
So that I can monitor the bridge in a browser.

**Acceptance Criteria:**

**Given** dashboard service running
**When** I open the UI
**Then** key account and strategy status is visible

### Story 5.5: Unit Test Coverage

As a developer,
I want JUnit 5 tests with 80%+ coverage on critical modules,
So that regressions are caught in CI.

**Acceptance Criteria:**

**Given** `mvn test` with coverage plugin
**When** report is generated
**Then** critical modules meet 80% line coverage target

### Story 5.6: Broker Reconnect and Error Handling

As an operator,
I want automatic reconnect on broker disconnect,
So that live trading recovers from transient failures.

**Acceptance Criteria:**

**Given** broker connection drops
**When** reconnect policy runs
**Then** connection restores without manual restart

### Story 5.7: User Documentation

As an end user,
I want setup and operations documentation,
So that I can configure brokers and run strategies independently.

**Acceptance Criteria:**

**Given** docs for production use
**When** a new user follows the guide
**Then** they can configure credentials and run a strategy end-to-end


---

## Epic 6: Backtesting Analytics

Advanced backtesting: Monte Carlo, Walk-Forward, correlation matrix, portfolio builder, HTML reports.

### Story 6.1: Advanced Performance Metrics ✅
Sharpe, Sortino, Profit Factor, Calmar ratios. Commission + slippage in BacktestEngine.

### Story 6.2: Monte Carlo Simulation ✅
1000+ runs, distribution P&L/DD/Sharpe, VaR 95%.

### Story 6.3: Walk-Forward Optimization ✅
Sliding IS/OOS windows, multi-objective, cross-validation.

### Story 6.4: Correlation Matrix ✅
Pearson P&L + drawdown correlation, heatmap export.

### Story 6.5: Portfolio Builder ✅
Mean-variance optimization, efficient frontier, min variance / max Sharpe.

### Story 6.6: StrategyQuant-Style HTML Reports ✅
Multi-tab report with Chart.js, equity curve, Monte Carlo overlay.

---

## Epic 7: StrategyQuant Replication (Genetic Engine)

Generate and test strategies automatically like StrategyQuant.

### Story 7.1: StrategyTemplate ✅
Skeleton strategy with mutable indicator slots.

### Story 7.2: Gene Pool ✅
SMA, EMA, RSI, ATR, ADX as mutable genes.

### Story 7.3: Genetic Engine ✅
Population, fitness, crossover, mutation, elitism, Virtual Threads.

### Story 7.4: Chromosome Encoding ✅
Strategy = DNA (indicators + params + conditions).

### Story 7.5: StrategyCodeGen ✅
Chromosome → compilable Java code.

### Story 7.6: Robustness Score ✅
Composite 0-100 (WFOOS 40% + MC 30% + Sharpe stability 20% + Sensitivity 10%).

### Story 7.7: Ranking Dashboard ✅
HTML interactive ranking with Chart.js filters and sort.

### Story 7.8: StrategyBuilder ✅
4 strategy types (Trend/MeanRev/Breakout/Momentum), config tree.

### Story 7.9: Parameter Sensitivity Analysis ✅
Parameter variation ±10/20/50%, stability scoring.

### Story 7.10: Multi-Market Test ✅
Validate across 7 FX pairs (EUR, GBP, USD, JPY, AUD, NZD, CHF).

### Story 7.11: Export One-Click ✅
./scripts/export-strategy.sh → generate, compile, backtest.

### Story 7.12: Batch Strategy Generator ✅
./scripts/batch-gen.sh → generate 500+ strategies, rank, validate, export HTML.

### Story 7.13: JForex Converter ✅
./scripts/convert-jforex.sh → convert existing JForex strategies.

### Story 7.14: Strategy Naming System ✅
Standardized naming: [ORG]_[FAM]_[SCOPE]_[SYM]_[DIR]_[TF]_[ID]_v[X.Y.Z]
StrategyID, StrategyRegistry, WF calibration frequency.


---

## Epic 8: Multi-Factor Strategy Enhancement

Integrate news, seasonality, and sentiment analysis into strategy generation and backtesting.

### Story 8.1: News Filter Gene (P0)
Ajouter un gene newsFilter au Chromosome avec les modes :
- SKIP_NEWS -> pas de trade 30 min avant/apres news majeure
- NEWS_MOMENTUM -> trade dans la direction post-news
- NEWS_REVERSAL -> fade the news
- OFF -> pas de filtre news (comportement actuel)

Backtest: Lire EconomicCalendar.java pour chaque barre, verifier s'il y a une news.

### Story 8.2: Seasonality Filter Gene (P1)
Ajouter un gene seasonFilter au Chromosome :
- MONTH_EFFECT -> Janvier/Euro fort, Septembre/volatilite
- DAY_OF_WEEK -> Lundi/gap, Vendredi/profit-taking
- HOUR_SESSION -> Londres/NY overlap, Asian session
- OFF -> pas de filtre saisonnalite

Backtest: Base sur bar.timestamp, pas de donnees externes.

### Story 8.3: StrategyTemplate News-Aware (P1)
Modifier StrategyTemplate pour utiliser les filtres news/seasonalite:
- Lire le calendrier economique depuis EconomicCalendar
- Verifier si une news est imminente
- Appliquer le filtre avant de generer un signal

### Story 8.4: Batch Generator News Mode (P2)
Ajouter l'option --factors news,seasonalite au batch-gen.sh.
Generer des strategies qui utilisent ces facteurs.

### Story 8.5: Sentiment Analysis API (P2 - future)
Integrer API sentiment externe (AlphaVantage, RavenPack) pour ponderer les signaux.


---

## Epic 9: VPS Live Trading Platform

Architecture: OpenClaw sur machine LOCALE, Live Trading sur VPS.
OpenClaw MONITORE les trades via HTTP/Telegram, n'est PAS sur le VPS.

```
Machine Locale (OpenClaw)                    VPS (Live Trading)
─────────────────────────                    ─────────────────
🤖 C-3PO (monitoring)                        🚀 LiveStrategyRunner
📊 Dashboard :8082                            (Java, zero OpenClaw)
🖥️ Laravel :8000                              |-- trade OANDA demo
🔔 Discord alerts                             |-- git pull strategies
📡 Telegram bot                               |-- health API :8083
    ↓                                          |-- Telegram alerts
    poll HTTP :8083 ← ← ← ← ← ← ← ← ← expose ┘
    recoit Telegram alerts ← ← ← ← ← ← envoie ┘
    Discord notifications
```

### Story 9.1: Self-Contained Live Runner (P0)
Le LiveStrategyRunner fonctionne SEUL sur le VPS, SANS OpenClaw:
- Lire les credentials depuis .env
- Logging fichier uniquement
- State persistence (sauvegarde auto, resume apres crash)
- Envoie les trades a Telegram (bot token dans .env)
- Expose un endpoint HTTP /health sur le port 8083

### Story 9.2: VPS Deployment Script (P0)
Script d'installation pour VPS vierge:
- Installer Java 21 + Maven
- Cloner trading-bridge depuis GitHub
- Compiler le projet
- Configurer .env avec creds OANDA
- Installer le service systemd pour le live runner
- Test de connexion OANDA

### Story 9.3: Remote Monitoring via OpenClaw (P1)
OpenClaw sur la machine locale MONITORE le VPS:
- C-3PO (agent dev) appelle /health sur le VPS toutes les 5 min
- Si le VPS repond pas → alerte Telegram + Discord #critical
- Le VPS envoie ses trades via Telegram (bot token)
- OpenClaw recoit les alertes de trade et les poste dans Discord #trades
- C-3PO peut demander: "TradeMaster, quel est le statut du VPS?" → appelle /health

### Story 9.4: Automated Strategy Deployment (P1)
Quand une strategie est validee (via batch-gen):
- Push sur GitHub (branche live/)
- Le VPS tire automatiquement les MAJ (git pull)
- Restart du live runner avec la nouvelle strategie
- Rollback automatique si la nouvelle strategie echoue

### Story 9.5: Strategy Selection for VPS (P2)
Quelles strategies envoyer sur le VPS:
- Meilleur Sharpe + Robustness du batch-gen
- Maximum 5 strategies simultanees (risque)
- Allocation de capital entre les strategies
