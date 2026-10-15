# Story 46.3: Idempotent Order Tagging, Partial-Fill Handling & Rejection Guard

Status: ready-for-dev

## Story

As a quantitative developer,
I want deterministic order tagging, duplicate submission blocking, atomic partial fill accumulation, and non-blocking handling of IBKR margin and exchange rejections,
So that the automated trading engine never fires duplicate orders on timeouts, tracks fractional-fill lifecycle accurately, and halts gracefully on margin deficits without infinite retry loops.

## Acceptance Criteria

1. **Given** any strategy attempting to submit an order:
   - A deterministic `orderTag` is generated using the pattern: `{symbol}-{strategyName}-{barTimestamp}-{side}` (e.g. `MES-SQUEEZE-20260823-1400-BUY`).
   - `IbkrOrderExecutionManager.java` checks SQLite for any existing record with that `orderTag`.
   - If an order exists in `SUBMITTED`, `PENDING`, or `FILLED` status, the submission is rejected immediately as a duplicate no-op.
2. **When** IBKR reports partial fills across multiple execution reports:
   - Each `execDetails()` callback increments `cumulativeFilledQuantity` and updates the volume-weighted average price (`vwap`).
   - The position status remains `PARTIALLY_FILLED` until `filledQuantity == totalQuantity`, at which point it transitions to `FILLED`.
3. **When** IBKR rejects an order with an error code (e.g. `Error 201: Order rejected - insufficient margin`, `Error 110: The price does not conform to the minimum tick size`):
   - The engine logs the structured error with full context in SQLite audit tables.
   - For margin rejections (`Error 201`), the affected strategy instance is marked `MARGIN_PAUSED` until the next bar evaluation cycle, preventing API spamming.
4. **And** unit test `IbkrOrderExecutionManagerTest.java` passes with 100% branch coverage on idempotency, partial fill accumulation, and rejection recovery.

## Tasks / Subtasks

- [ ] **Task 1: Deterministic Tagging & Idempotency Gate (`trading-core`, `trading-data`)** (AC: 1)
  - [ ] Implement `Order.generateCanonicalTag(String symbol, String strategy, Instant barTime, Side side)`.
  - [ ] Add unique index on `order_tag` in SQLite schema.
  - [ ] Add pre-submission duplicate check in `IbkrOrderExecutionManager.java`.
- [ ] **Task 2: Partial Fill Accumulation Engine (`trading-broker`)** (AC: 2)
  - [ ] Update `EWrapper.execDetails()` to compute cumulative fill quantity and VWAP.
  - [ ] Emit `OrderEvent.PARTIAL_FILL` to event stream and update position records.
- [ ] **Task 3: Error & Rejection Interception (`trading-broker`)** (AC: 3)
  - [ ] Implement error classifier in `DefaultEWrapper.error(int id, int errorCode, String errorMsg)`.
  - [ ] Map error `201` to `StrategyState.MARGIN_PAUSED`.
  - [ ] Log audit events to `audit_logs` table in SQLite.
- [ ] **Task 4: Unit & Stress Tests (`trading-broker`)** (AC: 4)
  - [ ] Author `IbkrOrderExecutionManagerTest.java` simulating rapid-fire duplicate signals and multi-part partial fills.
