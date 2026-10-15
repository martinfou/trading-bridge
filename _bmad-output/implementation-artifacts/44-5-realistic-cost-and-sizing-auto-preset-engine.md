# Story 44.5: Realistic Cost & Sizing Auto-Preset Engine per Asset Class

Status: ready-for-dev

## Story

As a trader,
I want the platform to automatically populate realistic, broker-accurate Capital, Lot Sizing (contracts vs lots vs shares), Commission (\$0.62 / \$0.07 / \$0.35), and Slippage (0.01%) whenever I select an instrument or strategy,
So that my backtest and execution models reflect true institutional trading costs and margin realities without requiring manual parameter lookups.

## Acceptance Criteria

1. **Given** the cost and sizing preset engine:
   - It defines standard institutional presets:
     - **`⚡ IBKR Futures Tiered`**: Commission = `$0.62`/side (\$1.24 round-trip), Lot Size = `1.0 contract`, Default Capital = `$50,000` (min \$10,000), Slippage = `0.01%` (1 tick).
     - **`💱 OANDA ECN Core`**: Commission = `$0.07`/side (per 0.01 lot), Lot Size = `0.01 lot` (1,000 units), Default Capital = `$1,000`, Slippage = `0.01%` (1 pip).
     - **`💱 OANDA Spread-Only`**: Commission = `$0.00`, Lot Size = `0.01 lot`, Default Capital = `$1,000`, Slippage = `0.015%` (1.5 pips).
     - **`📈 IBKR US Equities & ETFs`**: Commission = `$0.35` (tiered min), Lot Size = `10 shares`, Default Capital = `$10,000`, Slippage = `0.01%`.
     - **`⚙️ Custom Overrides`**: Preserves user-modified numeric entries.
2. **When** the user selects an instrument:
   - Selecting a Futures contract (e.g. `MES`, `MNQ`, `M2K`, `EMD`) automatically switches the preset to **`⚡ IBKR Futures Tiered`** and updates Capital, Lot Size, and Commission.
   - Selecting a Forex pair (e.g. `EUR/USD`) switches to **`💱 OANDA ECN Core`**.
   - Selecting an Equity/ETF (e.g. `IJR`, `VB`, `PLTR`) switches to **`📈 IBKR US Equities`**.
3. **When** the user manually overrides any numeric cost or sizing field:
   - The preset dropdown automatically shifts to `⚙️ Custom` and sets `isUserModified = true`.
4. **And** (Red Team Hardening: Anti-Overwrite Guard):
   - When switching between strategies or instruments while `isUserModified == true`, the engine locks and preserves the user's custom settings without wiping them out, unless the user explicitly clicks "Reset to Broker Defaults".
5. **And** backtests submitted to the backend receive the exact configured commission and slippage values.

## Tasks / Subtasks

- [ ] **Task 1: Preset Model & Defaults Definition (`desktop/src/types`, `composables`)** (AC: 1)
  - [ ] Create `CostPreset` interface and standard presets dictionary.
- [ ] **Task 2: UI Preset Selector & Dirty State Tracking in `BacktestForm.vue` & `WfaView.vue`** (AC: 2, 3, 4)
  - [ ] Add "Broker Cost Preset" dropdown with quick auto-fill pills.
  - [ ] Implement `isUserModified` dirty-state lock to prevent accidental overwrite of customized numbers.
  - [ ] Implement reactive auto-switch on instrument change when in non-dirty mode.
- [ ] **Task 3: Verification & E2E Tests** (AC: 5)
  - [ ] Verify execution costs across Futures, Forex, and Equities in backtest results.
