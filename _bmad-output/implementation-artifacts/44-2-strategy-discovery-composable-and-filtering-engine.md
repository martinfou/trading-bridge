# Story 44.2: Strategy Discovery Composable (`useStrategyCatalog.ts`) & Filtering Engine

Status: ready-for-dev

## Story

As a frontend developer,
I want a centralized reactive Vue composable (`useStrategyCatalog.ts`) that manages strategy fetching, multi-faceted filtering, fuzzy search indexing, and bi-directional instrument compatibility matching,
So that all views across the application share consistent, lightning-fast filtering logic with zero duplication.

## Acceptance Criteria

1. **Given** the new composable `desktop/src/composables/useStrategyCatalog.ts`:
   - It maintains reactive filter state:
     - `selectedAssetClass`: `'ALL' | 'FUTURES' | 'FOREX' | 'EQUITY'`
     - `selectedFamily`: `'ALL' | 'PROP' | 'LONG_TERM' | 'SQ_IMPORTED'`
     - `selectedStyle`: `'ALL' | 'TREND_FOLLOWING' | 'MEAN_REVERSION' | 'BREAKOUT' | 'MOMENTUM' | 'SEASONALITY'`
     - `searchQuery`: string (case-insensitive fuzzy matching across Strategy ID, Description, Indicators, and Recommended Symbols)
2. **When** filtering is applied:
   - `filteredStrategies` updates reactively in `< 5ms`.
   - Strategies are partitioned into grouped categories: `⚡ CME Futures`, `💱 Forex`, `📈 US Equities`, `Prop Mechanical`, `StrategyQuant Models`.
3. **When** bi-directional linking is triggered:
   - If an instrument is selected (e.g. `MES`): `compatibleStrategies` auto-highlights strategies supporting `FUTURES`.
   - If a strategy is selected (e.g. `FuturesTurnOfMonth`): `recommendedInstruments` returns `["MES", "MNQ", "M2K"]`.
4. **And** (Red Team Hardening: Multi-Asset Basket Isolation):
   - The composable provides `isCompatibleBasket(symbols)` and `validateBasketAssetClass(symbols)` guards.
   - Prevents selecting mixed-asset baskets (e.g., `MES` contracts + `EUR/USD` fractional lots in the same execution run) to eliminate sizing multiplier discrepancies.
5. **And** user filter preferences are persisted in `localStorage` under `trading_strategy_filters`.

## Tasks / Subtasks

- [ ] **Task 1: TypeScript Types & Interfaces (`desktop/src/types`)** (AC: 1)
  - [ ] Extend `Strategy` interface in `control-plane.ts` with `assetClasses`, `tradingStyle`, `timeframeSuitability`, `recommendedSymbols`, `complexity`.
- [ ] **Task 2: Composable Implementation (`desktop/src/composables`)** (AC: 1, 2, 3, 4, 5)
  - [ ] Author `useStrategyCatalog.ts` with state, getters, fuzzy search scoring, and localStorage persistence.
  - [ ] Implement `isCompatibleBasket` and single-asset-class isolation validation helper functions.
  - [ ] Implement bi-directional instrument compatibility helper functions.
- [ ] **Task 3: Unit Tests (`desktop/test`)** (AC: 2, 3, 4)
  - [ ] Author tests validating filter combinations, search edge-cases, basket isolation, and reactivity.
