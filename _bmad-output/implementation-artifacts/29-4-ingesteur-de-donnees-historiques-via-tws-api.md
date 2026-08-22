# Story 29.4: Ingesteur de données historiques Multi-Sources (IBKR TWS API, Yahoo Finance & Dukascopy)

Status: backlog

## Story

As a developer,
I want to download historical Futures (MES, M2K, EMD) and US Equities data directly from IBKR TWS API with rate-limiting protection, as well as via free fallback downloaders (Yahoo Finance/Stooq and Dukascopy),
So that I can run multi-asset backtests offline with zero friction and without depending exclusively on live TWS subscriptions.

## Acceptance Criteria

1. **Given** an active local IB Gateway or TWS connection:
   - Historical data requests for Futures (`SecType.FUT` on `CME`) and Equities (`SecType.STK` on `SMART`) call `reqHistoricalData`.
   - A minimum **500 ms pacing delay** is enforced between paginated chunks to comply with IBKR limits (max 50 requests / 10 min).
   - Received candles are written to `data/historical/futures/{symbol}_{timeframe}.csv` and `data/historical/stocks/{symbol}_{timeframe}.csv`.
2. **Given** offline mode or absence of an active TWS connection:
   - The system provides a **Yahoo Finance / Stooq Fallback Downloader** (`YahooFinanceDataLoader`) fetching H1 and Daily bars via direct HTTP requests for:
     - Futures proxies: `ES=F` (S&P 500), `RTY=F` (Russell 2000), `NQ=F` (Nasdaq 100), `EMD=F` (MidCap 400).
     - Equities / ETFs: `IWM` (Russell 2000 ETF), `MDY` (S&P MidCap ETF), individual Small/Mid Cap stock tickers.
     - Zero API keys required, parsing standard CSV/JSON endpoints.
3. **Given** high-density intraday backtesting requirements:
   - The system provides a **Dukascopy Fallback Ingester** (`DukascopyDataLoader`) fetching historical tick and minute/hourly data for Forex and major CFDs/indices.
4. **When** any downloader completes:
   - Output CSV files follow the standard schema: `timestamp` (ISO-8601 UTC), `open`, `high`, `low`, `close`, `volume`.
5. **When** a download fails or connection drops:
   - The system fails-fast, logs a descriptive error, and automatically rolls back partial corrupted files.

## Tasks / Subtasks

- [ ] **Task 1: Client d'ingestion historique IBKR (`trading-data`)** (AC: 1)
  - [ ] Développer `IbkrHistoricalDataLoader` supportant `FUT` et `STK`.
  - [ ] Intégrer le pacing de 500ms entre les requêtes paginées.
- [ ] **Task 2: Ingesteur gratuit Yahoo Finance & Stooq (`trading-data`)** (AC: 2, 4)
  - [ ] Développer `YahooFinanceDataLoader` pour barres H1 et Daily sur Futures proxies et Actions/ETFs.
  - [ ] Normaliser les symboles (`ES=F` -> `MES`, `RTY=F` -> `M2K`, `NQ=F` -> `MNQ`).
- [ ] **Task 3: Ingesteur Dukascopy (`trading-data`)** (AC: 3, 4)
  - [ ] Développer `DukascopyDataLoader` pour le téléchargement de données historiques haute résolution.
- [ ] **Task 4: Tests unitaires multi-sources** (AC: 1-5)
  - [ ] Écrire `IbkrHistoricalDataLoaderTest` avec `MockTcpGatewayServer`.
  - [ ] Écrire `YahooFinanceDataLoaderTest` avec mock HTTP responses.
  - [ ] Écrire `DukascopyDataLoaderTest`.
