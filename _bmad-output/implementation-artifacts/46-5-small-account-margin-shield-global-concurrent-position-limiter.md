# Story 46.5: Small-Account Margin Shield & Global Concurrent Position Limiter

Status: ready-for-dev

## Story

As a risk manager,
I want strict 1-micro contract position sizing, a hard global limit of 2 concurrent open positions, and a 50% max account initial margin shield,
So that accounts with smaller starting capital (\$3,000 to \$10,000) are rigorously insulated from margin calls, flash gap over-leverage, and broker forced liquidations.

## Acceptance Criteria

1. **Given** an account equity between \$3,000 and \$10,000:
   - `SmallAccountMarginGuard.java` sets the base order quantity strictly to `1` micro contract for any signal.
   - Fractional contracts or multi-contract scaling is blocked until account equity reaches defined milestone tiers (e.g. `equity >= $10,000` unlocks 2 contracts).
2. **When** a strategy generates a new entry signal:
   - The guard checks the current count of open positions across all strategies.
   - If `activePositionCount >= 2`, the new signal is rejected with reason `CONCURRENT_POSITION_LIMIT_REACHED`.
3. **When** evaluating total account risk:
   - The guard calculates: `totalRequiredInitialMargin = currentLockedMargin + newPositionInitialMargin`.
   - If `totalRequiredInitialMargin > (accountEquity * 0.50)` (50% margin ceiling), the order is rejected with reason `MARGIN_SHIELD_EXCEEDED`.
4. **And** unit test `SmallAccountMarginGuardTest.java` passes with 100% coverage across single position entries, 2-position concurrency saturation, and margin threshold rejections.

## Tasks / Subtasks

- [ ] **Task 1: Implement Small Account Margin Guard (`trading-core`)** (AC: 1, 2, 3)
  - [ ] Develop `SmallAccountMarginGuard.java` implementing pre-trade risk filter interface.
  - [ ] Implement `validateOrder(Order order, double accountEquity, List<Position> openPositions)`.
  - [ ] Add tiered equity threshold configuration for contract scaling.
- [ ] **Task 2: Integrate Guard into Execution Pipeline (`trading-broker`, `trading-runtime`)** (AC: 2, 3)
  - [ ] Intercept all outbound strategy orders through `SmallAccountMarginGuard`.
  - [ ] Log rejection metrics and reasons to SQLite audit ledger.
- [ ] **Task 3: Unit Tests & Boundary Stress Tests (`trading-core`)** (AC: 4)
  - [ ] Author `SmallAccountMarginGuardTest.java` verifying 1-micro constraints, 2-position caps, and 50% margin limit.
