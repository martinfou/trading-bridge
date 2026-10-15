# Epic 44: Strategy Discovery & Multi-Faceted Filtering System

## Executive Summary
As the Trading Bridge catalog expands across Forex, CME Electronic Futures (MES, MNQ, M2K, EMD), US Equities, Prop mechanical setups, and StrategyQuant machine-generated models, the strategy selection interface has become congested. 

**Epic 44** transforms strategy selection into an intuitive, multi-faceted discovery system featuring rich metadata tagging (Asset Class, Strategy Family, Trading Style, Indicators), smart contextual filtering by instrument, instant search with keyboard navigation, a reusable `<StrategySelector.vue>` component across Dashboard, WFA, Strategies Catalog, and Compare views, and **realistic default cost/sizing presets per asset class**.

---

## Realistic Cost, Position Sizing & Slippage Benchmark Table

| Asset Class | Instrument Examples | Lot Size / Unit Type | Typical Commission / side | Realistic Slippage | Default Recommended Capital |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **⚡ CME Micro Futures** | `MES`, `MNQ`, `M2K` | **1.0 – 5.0 contracts** (discrete integer) | **\$0.62** / contract (\$1.24 round-trip: \$0.25 IBKR + \$0.35 CME + \$0.02 NFA) | **0.005% – 0.010%** (1 tick / 0.25 pt) | **\$10,000 – \$50,000** |
| **⚡ CME E-mini Futures** | `EMD` (MidCap 400), `ES`, `NQ` | **1.0 contract** | **\$1.25** / contract (\$2.50 round-trip) | **0.020% – 0.030%** (0.10 pt) | **\$30,000 – \$50,000** |
| **💱 Forex Majors & Crosses** | `EUR/USD`, `GBP/USD`, `USD/JPY` | **0.01 – 0.10 lots** (1,000 – 10,000 units) | **\$0.035** / 0.01 lot (\$0.07 RT on ECN Core) or **\$0.00** on Spread accounts | **0.005% – 0.010%** (0.5 – 1.0 pip) | **\$500 – \$2,000** |
| **💱 Commodities Spot** | `XAU/USD` (Spot Gold) | **0.01 lot** (1 oz) | **\$0.02** / oz (\$0.04 RT) | **0.020% – 0.035%** (\$0.30 – \$0.80/oz) | **\$2,000 – \$5,000** |
| **📈 US Equities & ETFs** | `IJR`, `VB`, `SCHA`, `PLTR`, `TSLA` | **10 – 100 shares** | **\$0.35** per order (min \$0.0035/share on IBKR Tiered) | **0.005% – 0.020%** (\$0.01 – \$0.04/sh) | **\$5,000 – \$25,000** |

---

## User Stories & Architecture Breakdown

### Story 44.1: Backend Strategy Catalog Metadata Enrichment & Taxonomy API
* **Goal**: Expand `StrategyCatalog.java` and `LongTermStrategyCatalog.java` to associate every strategy with explicit asset class compatibility, trading styles, timeframe suitability, and default universes.
* **Scope**:
  - Add fields to `StrategyDescriptor`:
    - `assetClasses`: `["FUTURES"]`, `["FOREX"]`, `["EQUITY"]`, `["MULTI_ASSET"]`
    - `tradingStyle`: `"INTRADAY"`, `"SWING"`, `"SEASONALITY"`, `"TREND_FOLLOWING"`, `"MEAN_REVERSION"`, `"MOMENTUM"`
    - `recommendedSymbols`: `["MES", "MNQ"]`, `["EUR_USD", "GBP_USD"]`
    - `complexity`: `"BEGINNER"`, `"INTERMEDIATE"`, `"ADVANCED"`
  - Update `GET /api/strategies` REST endpoint to emit enriched metadata.
* **Acceptance Criteria**:
  - [x] All 25+ strategies return typed asset classes, trading style, indicators, and recommended symbols.
  - [x] Unit test `StrategyCatalogTest` asserts non-null metadata for all registered strategies.

---

### Story 44.2: Strategy Discovery Composable (`useStrategyCatalog.ts`) & Filtering Engine
* **Goal**: Author a centralized client-side filtering composable providing reactive facet filtering, fuzzy search indexing, and bi-directional instrument compatibility matching.
* **Scope**:
  - Author `desktop/src/composables/useStrategyCatalog.ts`.
  - Provide reactive filter states:
    - Active Asset Class (`ALL`, `FUTURES`, `FOREX`, `EQUITY`)
    - Active Family (`ALL`, `PROP`, `LONG_TERM`, `SQ_IMPORTED`)
    - Active Style (`TREND_FOLLOWING`, `MEAN_REVERSION`, `BREAKOUT`, `MOMENTUM`, `SEASONALITY`)
    - Search Query (case-insensitive fuzzy match across ID, indicators, description)
  - Bi-directional linking:
    - If instrument `MES` is selected $\to$ automatically highlight or filter strategies matching `FUTURES`.
    - If strategy `FuturesTurnOfMonth` is selected $\to$ automatically suggest `MES`, `MNQ`, `M2K`.
* **Acceptance Criteria**:
  - [x] Sub-millisecond filter execution on 100+ strategies.
  - [x] Clean reactive state management with localStorage persistence for user preferences.

---

### Story 44.3: Interactive `<StrategySelector.vue>` Component with Search & Filter Chips
* **Goal**: Build a modern, accessible UI component replacing the native `<select>` with a searchable modal/popover picker.
* **Scope**:
  - Horizontal filter chip bar: `[All] [⚡ Futures (IBKR)] [💱 Forex] [📈 Equities] [Prop Desk] [Trend] [Mean Rev]`
  - Instant search input with clear button and keyboard shortcut (`/` or `Cmd+K`).
  - Categorized list / grid cards with visual badges, indicator tags, and 1-line description tooltips.
  - Keyboard navigation (Arrow Up/Down, Enter to select, Esc to dismiss).
  - Selected state preview with chip removals and reset actions.
* **Acceptance Criteria**:
  - [x] Renders cleanly on dark theme adhering to design system.
  - [x] Fully keyboard accessible and responsive.

---

### Story 44.4: View Integration Across Dashboard, WFA, Strategies Catalog & Compare Views
* **Goal**: Replace legacy dropdowns in `BacktestForm.vue`, `WfaView.vue`, `StrategiesView.vue`, and `CompareView.vue` with the unified `<StrategySelector.vue>`.
* **Scope**:
  - Update `desktop/src/components/BacktestForm.vue`.
  - Update `desktop/src/views/WfaView.vue`.
  - Update `desktop/src/views/StrategiesView.vue` with synchronized filter chips.
  - Update `desktop/src/views/CompareView.vue`.
* **Acceptance Criteria**:
  - [x] Seamless strategy selection across all views.
  - [x] Preserves URL query parameters (`?strategyId=...&symbol=...`).

---

### Story 44.5: Realistic Cost & Sizing Auto-Preset Engine per Asset Class
* **Goal**: Enable automatic smart population of realistic Capital, Lot Size (contracts vs lots vs shares), Commission (\$0.62 / \$0.07 / \$0.35), and Slippage (0.01%) whenever an instrument or strategy is selected in any form.
* **Scope**:
  - Add a **"Cost & Sizing Preset"** selector:
    - `⚡ IBKR Futures Tiered` (\$0.62 comm, 1.0 contract, \$50k capital, 0.01% slippage)
    - `💱 OANDA ECN Core` (\$0.07 comm, 0.01 lot, \$1k capital, 0.01% slippage)
    - `💱 OANDA Spread-Only` (\$0.00 comm, 0.01 lot, \$1k capital, 0.015% slippage)
    - `📈 IBKR US Equities` (\$0.35 comm, 10 shares, \$10k capital, 0.01% slippage)
    - `⚙️ Custom User Overrides`
  - Automatically switch preset defaults on instrument change while preserving custom edits.
* **Acceptance Criteria**:
  - [x] Switching between `MES` and `EUR/USD` instantly applies realistic broker-accurate numbers.
  - [x] Zero risk of executing 1 contract of MES on an unrealistic $1,000 capital balance.

---

## Blue Team / Red Team Hardening Matrix

| # | Attack Vector | Severity | Hardened Mitigation | Enforcing Story |
| :--- | :--- | :---: | :--- | :---: |
| **1** | **Multi-Asset Multiplier Bleed** (Mixed contracts/lots in single basket) | 🔴 HIGH | Enforce single-asset-class basket isolation in composable & UI (`isCompatibleBasket`). | **44.2 / 44.5** |
| **2** | **Unannotated SQ Ingestion** (Null metadata crash on imported models) | 🟠 MED | Dynamic fallback inference via `AssetClassDetector.inferFromSymbol(defaultSymbol)`. | **44.1 / 44.2** |
| **3** | **Preset Wipeout Ambush** (Overwriting user custom cost settings on strategy change) | 🟠 MED | `isUserModified` dirty tracking lock that preserves customized numbers until explicit reset. | **44.5** |
| **4** | **Keyboard Trap & Focus Loss** (Loss of focus on popover close / escape) | 🟡 LOW | WAI-ARIA combobox compliant DOM focus restoration to trigger element on dismiss. | **44.3** |
| **5** | **Deep-Link State Corruption** (Invalid `?strategyId=XYZ` in URL) | 🟡 LOW | Graceful fallback to default catalog + non-blocking warning toast. | **44.4** |

---

## Sprint Execution Plan & Verification

1. **Phase 1**: Backend Metadata Enrichment (`trading-strategies`, `trading-runtime`).
2. **Phase 2**: Frontend Composable & Component (`useStrategyCatalog.ts`, `StrategySelector.vue`).
3. **Phase 3**: Cost Preset Engine & View Integrations across Dashboard, WFA, Strategies, and Compare.
4. **Phase 4**: Visual E2E verification via Playwright test suite.
