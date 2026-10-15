# Epics & Stories — Epic 44: Strategy Discovery & Multi-Faceted Filtering System

> Phase 3 — Solutioning & Epic Refinement  
> Project: Trading Bridge — Multi-Asset Trading Platform  
> Module: `trading-strategies`, `trading-runtime`, `desktop/`  
> Objective: Provide structured taxonomy metadata, multi-faceted filtering, fuzzy search, an interactive strategy selector component, and realistic cost/position sizing auto-presets.

---

## Epic 44 Overview

As the Trading Bridge strategy catalog expands across Forex, CME Electronic Futures (`MES`, `MNQ`, `M2K`, `EMD`), US Equities, Prop mechanical setups, and StrategyQuant machine-generated models, traders need an effortless way to discover, filter, and configure strategies without navigating a massive flat dropdown.

---

## User Stories Inventory

| Story ID | Title | Priority | Effort | Description | Dependencies |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **`44-1`** | **Backend Strategy Catalog Metadata Enrichment & Taxonomy API** | P0 | M | Enrich `StrategyCatalog.java` and `LongTermStrategyCatalog.java` with structured metadata (`assetClasses`, `tradingStyle`, `timeframeSuitability`, `recommendedSymbols`, `complexity`) and expose via `GET /api/strategies?assetClass=...`. | — |
| **`44-2`** | **Strategy Discovery Composable (`useStrategyCatalog.ts`) & Filtering Engine** | P0 | S | Centralized Vue composable with reactive facet filtering (Asset Class, Family, Style), sub-5ms fuzzy search scoring, bi-directional instrument/strategy linking, and localStorage filter persistence. | 44.1 |
| **`44-3`** | **Interactive `<StrategySelector.vue>` Component with Search & Filter Chips** | P0 | M | Reusable modal/popover component with quick filter chips (`[All]`, `[⚡ Futures]`, `[💱 Forex]`, `[📈 Equities]`, `[Trend]`, `[Mean Rev]`), auto-focus search, indicator tags, and keyboard navigation (`Arrow Up/Down`, `Enter`, `Esc`, `Cmd+K`). | 44.2 |
| **`44-4`** | **View Integration Across Dashboard, WFA, Strategies Catalog & Compare Views** | P1 | S | Replace legacy flat selects across `BacktestForm.vue`, `WfaView.vue`, `StrategiesView.vue`, and `CompareView.vue` while preserving deep-link URL parameters (`?strategyId=...&symbol=...`). | 44.3 |
| **`44-5`** | **Realistic Cost & Sizing Auto-Preset Engine per Asset Class** | P0 | S | Auto-populate broker-accurate Capital, Lot Size (contracts vs lots vs shares), Commission (\$0.62 / \$0.07 / \$0.35), and Slippage (0.01%) on instrument/strategy selection with a built-in Preset switcher. | 44.2 |

---

## Detailed Story Breakdowns

### Story 44.1: Backend Strategy Catalog Metadata Enrichment & Taxonomy API
* **File:** [`44-1-backend-strategy-catalog-metadata-enrichment-taxonomy-api.md`](file:///Volumes/T7/src/trading-bridge/_bmad-output/implementation-artifacts/44-1-backend-strategy-catalog-metadata-enrichment-taxonomy-api.md)
* **Goal:** Extend Java catalog with typed metadata and provide REST endpoint filtering.
* **Gherkin Acceptance Criteria:**
  - **Given** 25+ strategies in `StrategyCatalog.java` and `LongTermStrategyCatalog.java`,
  - **When** `GET /api/strategies` is invoked,
  - **Then** each strategy object includes non-empty `assetClasses`, `tradingStyle`, `timeframeSuitability`, `recommendedSymbols`, and `complexity`.
  - **When** `GET /api/strategies?assetClass=FUTURES` is invoked,
  - **Then** only strategies compatible with Futures (`FuturesTurnOfMonth`, `FuturesOpeningRangeBreakout`, `LtRangeBreakout`, `LtEfficiencyRatio`) are returned.

### Story 44.2: Strategy Discovery Composable (`useStrategyCatalog.ts`) & Filtering Engine
* **File:** [`44-2-strategy-discovery-composable-and-filtering-engine.md`](file:///Volumes/T7/src/trading-bridge/_bmad-output/implementation-artifacts/44-2-strategy-discovery-composable-and-filtering-engine.md)
* **Goal:** Centralized reactive state machine for strategy searching and facet filtering.
* **Gherkin Acceptance Criteria:**
  - **Given** `useStrategyCatalog.ts`,
  - **When** user enters search terms or toggles filter chips,
  - **Then** `filteredStrategies` recomputes instantly (< 5ms) without freezing the UI thread.
  - **When** an instrument like `MES` is selected,
  - **Then** `compatibleStrategies` highlights and sorts Futures-compatible strategies first.

### Story 44.3: Interactive `<StrategySelector.vue>` Component
* **File:** [`44-3-interactive-strategy-selector-component.md`](file:///Volumes/T7/src/trading-bridge/_bmad-output/implementation-artifacts/44-3-interactive-strategy-selector-component.md)
* **Goal:** A modern, accessible strategy picker replacing standard `<select>`.
* **Gherkin Acceptance Criteria:**
  - **Given** `<StrategySelector.vue>`,
  - **When** user triggers the picker (click or `Cmd+K` / `/`),
  - **Then** a popover renders with quick filter chips, search input, and grouped strategy cards.
  - **When** user navigates with `Arrow Up`/`Arrow Down` and presses `Enter`,
  - **Then** the selected strategy is emitted and the popover closes.

### Story 44.4: View Integration Across Dashboard, WFA, Strategies Catalog & Compare Views
* **File:** [`44-4-view-integration-dashboard-wfa-strategies-compare.md`](file:///Volumes/T7/src/trading-bridge/_bmad-output/implementation-artifacts/44-4-view-integration-dashboard-wfa-strategies-compare.md)
* **Goal:** Unify strategy selection across all four core desktop views.
* **Gherkin Acceptance Criteria:**
  - **Given** `BacktestForm.vue`, `WfaView.vue`, `StrategiesView.vue`, and `CompareView.vue`,
  - **When** any view renders,
  - **Then** `<StrategySelector.vue>` is used consistently.
  - **When** URL query params (`?strategyId=FuturesTurnOfMonth&symbol=MES`) are present,
  - **Then** the selector initializes with the pre-selected strategy and symbol.

### Story 44.5: Realistic Cost & Sizing Auto-Preset Engine per Asset Class
* **File:** [`44-5-realistic-cost-and-sizing-auto-preset-engine.md`](file:///Volumes/T7/src/trading-bridge/_bmad-output/implementation-artifacts/44-5-realistic-cost-and-sizing-auto-preset-engine.md)
* **Goal:** Eliminate manual entry of broker trading fees and sizing mismatches.
* **Gherkin Acceptance Criteria:**
  - **Given** the cost preset engine,
  - **When** a user picks `MES` or a Futures strategy,
  - **Then** Capital auto-populates to `$50,000`, Lot Size to `1.0 contract`, and Commission to `$0.62` (\$1.24 round-trip).
  - **When** a user picks `EUR/USD`,
  - **Then** Capital auto-populates to `$1,000`, Lot Size to `0.01 lot`, and Commission to `$0.07`.
  - **When** user modifies any cost field,
  - **Then** the preset label updates to `⚙️ Custom` with `isUserModified = true` lock.

---

## Blue Team / Red Team Hardening Matrix

| # | Attack Vector | Severity | Hardened Mitigation | Enforcing Story |
| :--- | :--- | :---: | :--- | :---: |
| **1** | **Multi-Asset Multiplier Bleed** (Mixed contracts/lots in single basket) | 🔴 HIGH | Enforce single-asset-class basket isolation in composable & UI (`isCompatibleBasket`). | **44.2 / 44.5** |
| **2** | **Unannotated SQ Ingestion** (Null metadata crash on imported models) | 🟠 MED | Dynamic fallback inference via `AssetClassDetector.inferFromSymbol(defaultSymbol)`. | **44.1 / 44.2** |
| **3** | **Preset Wipeout Ambush** (Overwriting user custom cost settings on strategy change) | 🟠 MED | `isUserModified` dirty tracking lock that preserves customized numbers until explicit reset. | **44.5** |
| **4** | **Keyboard Trap & Focus Loss** (Loss of focus on popover close / escape) | 🟡 LOW | WAI-ARIA combobox compliant DOM focus restoration to trigger element on dismiss. | **44.3** |
| **5** | **Deep-Link State Corruption** (Invalid `?strategyId=XYZ` in URL) | 🟡 LOW | Graceful fallback to default catalog + non-blocking warning toast. | **44.4** |
