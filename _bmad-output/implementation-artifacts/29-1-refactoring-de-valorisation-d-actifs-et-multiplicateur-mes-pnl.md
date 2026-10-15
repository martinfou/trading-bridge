# Story 29.1: Refactoring de valorisation d'actifs, Multiplicateurs Futures (MES, M2K, EMD, MNQ) et Actions US

Status: backlog

## Story

As a quantitative trader,
I want to configure Futures point multipliers (MES, M2K Russell 2000, EMD MidCap 400, MNQ) and US Equities share valuation in backtests,
So that my multi-asset strategy returns and PnL are calculated accurately.

## Acceptance Criteria

1. **Given** a configuration file `data/runtime/futures-contracts.json` containing specifications for Small Cap, Mid Cap, Large Cap, and Tech Futures:
   ```json
   {
     "contracts": {
       "MES": {"multiplier": 5.0, "tickSize": 0.25, "currency": "USD", "initialMargin": 1500.0, "maintenanceMargin": 1200.0},
       "M2K": {"multiplier": 5.0, "tickSize": 0.10, "currency": "USD", "initialMargin": 1400.0, "maintenanceMargin": 1100.0},
       "EMD": {"multiplier": 100.0, "tickSize": 0.10, "currency": "USD", "initialMargin": 15000.0, "maintenanceMargin": 12000.0},
       "MNQ": {"multiplier": 2.0, "tickSize": 0.25, "currency": "USD", "initialMargin": 1800.0, "maintenanceMargin": 1400.0}
     }
   }
   ```
2. **When** the backtest engine starts for any configured Futures symbol.
3. **Then** the `FuturesRegistry` loads the configuration and validates its JSON schema.
4. **Given** the `AssetValuationModel` interface and its three implementations:
   - `ForexValuationModel` : Forex pips/lots conversion.
   - `FuturesValuationModel` : Fixed point multiplier $\times$ discrete integer contracts ($\ge 1.0$).
   - `StockValuationModel` : US Equities valuation ($1.0$ multiplier $\times$ discrete integer shares $\ge 1$).
5. **When** calculating PnL:
   - **Small Cap Futures (`M2K`)**: BUY 1 contract at 2000.0, SELL at 2010.0 $\rightarrow$ PnL = **50.00 USD** ($10.0 \text{ pts} \times 5.0 \times 1$).
   - **Mid Cap Futures (`EMD`)**: BUY 1 contract at 2800.0, SELL at 2802.0 $\rightarrow$ PnL = **200.00 USD** ($2.0 \text{ pts} \times 100.0 \times 1$).
   - **US Equities (`IWM` / Small Cap stock)**: BUY 100 shares at 200.00, SELL at 205.00 $\rightarrow$ PnL = **500.00 USD** ($5.00 \times 1.0 \times 100$).
6. **When** the existing `GoldenBacktestTest` is run.
7. **Then** the PnL delta on Forex is strictly `0.00` (zero regression on OANDA/Forex logic).

## Tasks / Subtasks

- [ ] **Task 1: Schéma et loader de contrats Futures (`trading-core`)** (AC: 1, 2, 3)
  - [ ] Créer `data/runtime/futures-contracts.json` avec MES, M2K (Small Cap), EMD (Mid Cap), MNQ (Tech).
  - [ ] Développer `FuturesRegistry` avec schéma de validation JSON.
- [ ] **Task 2: Interface `AssetValuationModel` et implémentations (`trading-core`)** (AC: 4, 5)
  - [ ] Définir `AssetValuationModel` (`double calculatePnl(Position.Side side, double entryPrice, double exitPrice, double quantity)`).
  - [ ] Implémenter `ForexValuationModel` (existant conservé).
  - [ ] Implémenter `FuturesValuationModel` (multiplicateur de contrat et validation d'entiers).
  - [ ] Implémenter `StockValuationModel` (valorisation par action et validation de quantité d'actions entières).
- [ ] **Task 3: Intégration dans `BacktestEngine` et tests** (AC: 6, 7)
  - [ ] Injecter `AssetValuationModel` selon l'instrument dans `BacktestEngine`.
  - [ ] Valider `FuturesValuationModelTest` et `StockValuationModelTest`.
  - [ ] Exécuter `GoldenBacktestTest` (0.0 delta Forex).
