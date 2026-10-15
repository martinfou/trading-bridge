# Story 45.4: CME Globex Session Calendar & Margin Regulation Alignment (PDT Exemption)

Status: ready-for-dev

## Story

As a trader running futures strategies,
I want the engine to use CME Globex trading hours (Sunday 6pm ET open, daily 5pm-6pm maintenance pause) and exempt futures accounts from FINRA Pattern Day Trader (PDT) restrictions,
So that bar filtering mirrors CME trading schedules and micro futures accounts (<$25k) are not falsely penalized or blocked by equity day-trading rules.

## Acceptance Criteria

1. **Given** `CmeGlobexMarketCalendar.java`:
   - Accurately models CME Globex trading hours: Sunday 18:00 ET to Friday 17:00 ET, excluding Saturday and daily 17:00–18:00 ET maintenance pause.
   - Categorizes bars into RTH (09:30–16:00 ET) and ETH (Overnight).
2. **When** `MarginTracker.java` evaluates margin and day-trade rules:
   - For `AssetClass.FUTURES`, PDT rule checking is bypassed (`pdtRestricted = false`), allowing accounts with <$25,000 to execute unlimited day trades.
   - Initial ($1,200/contract) and maintenance ($1,000/contract) margin requirements remain strictly enforced.
3. **And** unit tests `CmeGlobexMarketCalendarTest.java` and `FuturesMarginPdtExemptionTest.java` pass.

## Tasks / Subtasks

- [ ] **Task 1: Author CmeGlobexMarketCalendar (`trading-core`)** (AC: 1)
  - [ ] Create `CmeGlobexMarketCalendar.java` with Globex session validation and RTH/ETH classification.
  - [ ] Update `BacktestEngine.java` to use asset-class-aware market calendar resolution.
- [ ] **Task 2: Update MarginTracker for PDT Exemption (`trading-core`)** (AC: 2)
  - [ ] Modify `MarginTracker.evaluate()` to check asset class and exempt `FUTURES` from PDT checks.
- [ ] **Task 3: Unit Tests (`trading-core`, `trading-backtest`)** (AC: 3)
  - [ ] Author tests validating Globex bar acceptance and unlimited day trades on small futures accounts.
