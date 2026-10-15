# Story 29.2: Simulation des marges et Liquidation forcée (Futures & Actions Reg-T)

Status: backlog

## Story

As a risk manager,
I want to simulate Initial/Maintenance margin requirements for Futures (Small/Mid/Large caps) and Reg-T margin for Equities with forced liquidation,
So that multi-asset leverage and liquidation risk are realistically modeled.

## Acceptance Criteria

1. **Given** Futures margin requirements with +5% safety buffer:
   - `MES`: Initial 1500 USD, Maintenance threshold **1260 USD** ($1200 \times 1.05$).
   - `M2K` (Small Cap): Initial 1400 USD, Maintenance threshold **1155 USD** ($1100 \times 1.05$).
   - `EMD` (Mid Cap): Initial 15000 USD, Maintenance threshold **12600 USD** ($12000 \times 1.05$).
2. **Given** US Equities margin rules (Reg-T):
   - Intraday margin requirement: **25%** of market value.
   - Overnight margin requirement: **50%** of market value.
   - Pattern Day Trader (PDT) warning if account equity $< 25,000$ USD on day-trade orders.
3. **When** an order is submitted (Futures contract or Stock shares):
   - `MarginTracker` verifies available free equity $\ge$ Initial Margin Requirement.
   - Rejects the order with `INSUFFICIENT_INITIAL_MARGIN` if equity is below requirement.
4. **When** during backtesting, simulated account equity drops below the Maintenance Margin threshold (Futures) or below 25% minimum equity (Equities) at bar close:
   - `BacktestEngine` triggers an emergency market liquidation order executed at the **Open price of the next bar**.
   - Logs a `LIQUIDATION` event with asset type, timestamp, and equity level in the trade journal.
   - Deducts standard commission and slippage.

## Tasks / Subtasks

- [ ] **Task 1: Modèles de marge Multi-Actifs (`trading-core`)** (AC: 1, 2, 3)
  - [ ] Créer `FuturesMarginRequirement` (Initial, Maintenance).
  - [ ] Créer `StockMarginRequirement` (Intraday 25%, Overnight 50%, PDT rule check).
  - [ ] Implémenter `MarginTracker` unifié vérifiant la marge libre selon la classe d'actif.
- [ ] **Task 2: Détection d'appel de marge et liquidation forcée (`trading-backtest`)** (AC: 4)
  - [ ] Évaluer l'équité totale à chaque clôture de barre.
  - [ ] Déclencher la liquidation d'urgence à l'Open suivant en cas de déficit.
  - [ ] Enregistrer l'événement `LIQUIDATION`.
- [ ] **Task 3: Tests unitaires** (AC: 1-4)
  - [ ] Écrire `FuturesMarginSimulationTest` (MES, M2K, EMD).
  - [ ] Écrire `StockMarginSimulationTest` (Reg-T 50%, 25%, liquidation et PDT check).
