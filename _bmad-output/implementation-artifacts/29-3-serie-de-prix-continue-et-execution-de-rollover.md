# Story 29.3: Série de prix continue et exécution de Rollover (Futures CME & Bypass Actions)

Status: backlog

## Story

As a long-term position trader,
I want to stitch quarterly Futures contracts (MES, M2K, EMD, MNQ) at T-10 with simulated rollover, while bypassing rollover for non-expiring Equities,
So that my multi-asset backtests reflect realistic contract lifecycles.

## Acceptance Criteria

1. **Given** CME quarterly contract expiry calendar rules: Futures contracts (`MES`, `M2K`, `EMD`, `MNQ`) expire on the **3rd Friday of March (`H`), June (`M`), September (`U`), December (`Z`)**.
2. **When** `FuturesContinuousSeriesBuilder` stitches historical files for any CME Futures symbol, the transition occurs at exactly **$T-10$ calendar days** prior to the 3rd Friday of the expiring contract month.
3. **When** the transition bar is reached and an open position exists on the expiring Futures contract:
   - The engine force-closes Leg A at the close price of the transition bar.
   - The engine immediately opens Leg B in the same direction at the same transition price.
   - Both transactions are linked with a shared UUID `rolloverGroupId` and tag `reason = ROLLOVER`.
   - Double commission is charged (flat fee $\times$ 2).
4. **When** performance statistics (Win Rate, Total Trades, Profit Factor) are calculated:
   - The two rollover legs are consolidated into a single continuous logical trade.
5. **Given** an Equities symbol (e.g. `IWM`, `MDY`, `AAPL`):
   - The backtest engine detects `AssetClass.EQUITY` and **bypasses contract rollover entirely** (shares have no expiration).
6. **When** a subsequent contract CSV file (e.g. `M2KU6.csv`) is missing on disk:
   - The system fails-fast with a clear `MissingContractDataException`.

## Tasks / Subtasks

- [ ] **Task 1: Concaténation CME et bypass Equities (`trading-data`)** (AC: 1, 2, 5, 6)
  - [ ] Étendre `CmeFuturesCalendar` pour tous les symboles CME (MES, M2K, EMD, MNQ).
  - [ ] Implémenter le bypass automatique de rollover pour les actions (`AssetClass.EQUITY`).
- [ ] **Task 2: Exécution et consolidation de Rollover (`trading-backtest`)** (AC: 3, 4)
  - [ ] Gérer la fermeture/réouverture simultanée avec `rolloverGroupId`.
  - [ ] Consolider les jambes de rollover dans les métriques de performance.
- [ ] **Task 3: Tests unitaires et d'intégration** (AC: 1-6)
  - [ ] `FuturesRolloverBacktestTest` sur M2K et EMD.
  - [ ] `StockContinuousSeriesTest` vérifiant l'absence de rollover sur actions.
