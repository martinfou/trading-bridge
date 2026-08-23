# Story 44.4: View Integration across Dashboard, WFA, Strategies Catalog & Compare Views

Status: ready-for-dev

## Story

As a user,
I want the new strategy selector component seamlessly integrated across all platform views (Dashboard, Walk-Forward Analysis, Strategies Catalog, and Compare),
So that I have a consistent, modern strategy discovery and selection experience everywhere in the application.

## Acceptance Criteria

1. **Given** the core application views:
   - `desktop/src/components/BacktestForm.vue` (used in Dashboard)
   - `desktop/src/views/WfaView.vue` (Walk-Forward Analysis)
   - `desktop/src/views/StrategiesView.vue` (Strategies Catalog)
   - `desktop/src/views/CompareView.vue` (Strategy Comparison)
2. **When** viewing any of these pages:
   - The legacy `<select>` dropdown is replaced with the enhanced `<StrategySelector.vue>` component.
3. **When** navigating via deep-link query parameters (e.g. `/#/dashboard?strategyId=FuturesTurnOfMonth&symbol=MES`):
   - The strategy selector initializes with the pre-selected strategy and renders appropriately.
4. **And** (Red Team Hardening: Deep-Link Validation Guard):
   - If an invalid or deleted `?strategyId=UnknownStrategy` is provided in the URL, the component logs a warning, falls back gracefully to the first valid strategy or empty state without crashing, and displays a non-blocking toast.
5. **And** selecting a strategy in one view synchronizes the appropriate symbol universe and cost presets.

## Tasks / Subtasks

- [ ] **Task 1: Integrate into `BacktestForm.vue`** (AC: 1, 2, 3, 4)
  - [ ] Replace native select with `<StrategySelector v-model="selectedStrategy" :filter-asset-class="selectedAssetClass" />`.
  - [ ] Implement URL parameter validation and fallback guard.
- [ ] **Task 2: Integrate into `WfaView.vue`** (AC: 1, 2)
  - [ ] Embed `<StrategySelector>` in WFA parameter optimization setup.
- [ ] **Task 3: Synchronize with `StrategiesView.vue` and `CompareView.vue`** (AC: 1, 2, 5)
  - [ ] Add facet filter chips to the main Strategies Catalog grid.
  - [ ] Update multi-strategy comparison picker.
