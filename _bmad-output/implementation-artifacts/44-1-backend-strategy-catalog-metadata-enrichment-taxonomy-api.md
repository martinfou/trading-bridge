# Story 44.1: Backend Strategy Catalog Metadata Enrichment & Taxonomy API

Status: ready-for-dev

## Story

As a trader and quantitative researcher,
I want the backend strategy catalog to expose structured metadata for each strategy (compatible asset classes, trading styles, timeframe suitability, recommended instruments, and complexity),
So that the frontend and API consumers can filter, search, and categorize strategies without hardcoded client-side rules.

## Acceptance Criteria

1. **Given** the strategy catalogs (`StrategyCatalog.java` and `LongTermStrategyCatalog.java`):
   - Every registered strategy is enriched with:
     - `assetClasses`: List of `AssetClass` (`FUTURES`, `FOREX`, `EQUITY`, `MULTI_ASSET`)
     - `tradingStyle`: String (`"TREND_FOLLOWING"`, `"MEAN_REVERSION"`, `"BREAKOUT"`, `"MOMENTUM"`, `"SEASONALITY"`, `"ARBITRAGE"`)
     - `timeframeSuitability`: List of strings (`"H1"`, `"M1"`, `"M30"`, `"D1"`)
     - `recommendedSymbols`: List of canonical symbols (e.g. `["MES", "MNQ", "M2K"]` for Futures ORB/TOTM; `["EUR_USD", "GBP_USD"]` for Forex)
     - `complexity`: String (`"BEGINNER"`, `"INTERMEDIATE"`, `"ADVANCED"`)
2. **When** querying `GET /api/strategies`:
   - The JSON response contains the full array of enriched strategies with the new taxonomy fields.
3. **When** querying `GET /api/strategies?assetClass=FUTURES`:
   - The endpoint optionally filters and returns only strategies supporting `FUTURES`.
4. **And** backward compatibility is strictly maintained for legacy fields (`id`, `family`, `defaultSymbol`, `type`, `indicators`, `description`).
5. **And** (Red Team Hardening): Unannotated or dynamically imported StrategyQuant models (`SQ_IMPORTED` / custom strategies) must use an automated fallback inference engine (`AssetClassDetector.inferFromSymbol(defaultSymbol)`) so that `assetClasses` and `tradingStyle` are never `null` or unhandled.
6. **And** unit test `StrategyCatalogTest` asserts non-empty metadata across 100% of registered and imported strategies.

## Tasks / Subtasks

- [ ] **Task 1: Extend Strategy Descriptor & Domain Records (`trading-strategies`, `trading-core`)** (AC: 1, 4, 5)
  - [ ] Add `StrategyDescriptor` record or update `StrategyMetadata` with `assetClasses`, `tradingStyle`, `timeframeSuitability`, `recommendedSymbols`, `complexity`.
  - [ ] Implement `AssetClassDetector.inferFromSymbol(defaultSymbol)` fallback logic for unannotated/imported strategies.
  - [ ] Populate taxonomy mapping for all 25+ strategies in `StrategyCatalog.java` and `LongTermStrategyCatalog.java`.
- [ ] **Task 2: REST Controller & Filtering (`trading-runtime`)** (AC: 2, 3)
  - [ ] Update `ControlPlaneServer.java` `/api/strategies` handler to serialize new taxonomy fields and support `?assetClass=` query param.
- [ ] **Task 3: Unit Testing & Verification (`trading-strategies`, `trading-runtime`)** (AC: 6)
  - [ ] Author `StrategyTaxonomyTest` asserting schema validity, non-null guarantees, and fallback coverage.
