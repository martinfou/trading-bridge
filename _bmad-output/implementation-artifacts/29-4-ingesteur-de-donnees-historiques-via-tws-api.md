# Story 29.4: Ingesteur de données historiques via TWS API (Futures & Actions US)

Status: backlog

## Story

As a developer,
I want to download historical Futures (MES, M2K, EMD) and US Equities data directly from IBKR with rate-limiting protection,
So that I can run multi-asset backtests offline without manual CSV files.

## Acceptance Criteria

1. **Given** an active local IB Gateway or TWS connection.
2. **When** historical data is requested for:
   - **Futures** : Resolved contract type `SecType.FUT` on `CME` (e.g. `MES`, `M2K`, `EMD`).
   - **Equities** : Resolved contract type `SecType.STK` on exchange `SMART` and currency `USD` (e.g. `IWM`, `MDY`, `AAPL`).
3. **Then** the client calls `reqHistoricalData` with the appropriate contract parameters.
4. **And** `IbkrHistoricalDataLoader` applies a minimum **500 ms delay** between paginated chunk requests to comply with IBKR pacing rules (max 50 requests per 10 minutes).
5. **When** callbacks `historicalData` and `historicalDataEnd` are received:
   - Futures candles are written to `data/historical/futures/{symbol}_{timeframe}.csv`.
   - Stock candles are written to `data/historical/stocks/{symbol}_{timeframe}.csv`.
6. **When** the gateway is not reachable, the system fails-fast, logs `IllegalStateException: Failed to connect to IB Gateway`, and cleans up partial corrupted files.

## Tasks / Subtasks

- [ ] **Task 1: Client d'ingestion historique multi-actifs (`trading-data`)** (AC: 1, 2, 3, 4)
  - [ ] Développer `IbkrHistoricalDataLoader` supportant `FUT` et `STK`.
  - [ ] Intégrer le pacing de 500ms entre les requêtes.
- [ ] **Task 2: Écriture CSV partitionnée par classe d'actif (`trading-data`)** (AC: 5)
  - [ ] Écrire dans `data/historical/futures/` et `data/historical/stocks/`.
- [ ] **Task 3: Tests unitaires avec Mock Gateway** (AC: 6)
  - [ ] Écrire `IbkrHistoricalDataLoaderTest` pour Futures et Actions.
