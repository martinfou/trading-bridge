# Story 46.4: Autonomous CME Quarterly Rollover & Calendar Spread Engine

Status: ready-for-dev

## Story

As a swing trader,
I want the system to autonomously detect quarterly futures contract expirations (March `H`, June `M`, September `U`, December `Z`), monitor volume migration across contract months, and execute calendar spread orders (`COMBO`),
So that long-running swing positions are smoothly transitioned to the active front-month without manual intervention, avoiding delivery expiration risks and minimizing bid-ask spread bleed.

## Acceptance Criteria

1. **Given** an open position on an active CME futures contract (e.g. `MESH26`):
   - `CmeAutoRolloverManager.java` begins monitoring rollover eligibility 8 calendar days prior to contract expiration (Third Friday of the expiration month).
2. **When** volume migration begins:
   - The daemon requests hourly volume for both the expiring front-month contract (`MESH26`) and the next quarterly contract (`MESM26`).
   - When the next contract's volume exceeds the front-month volume for 2 consecutive hourly bars, a rollover trigger is armed.
3. **When** the rollover executes:
   - The system submits an IBKR `COMBO` contract (Calendar Spread: Sell expiring contract leg, Buy next contract leg).
   - Once filled, the strategy's internal position ticket is updated with the new contract symbol (`MESM26`).
   - The entry price and stop loss levels are adjusted by the executed calendar spread price differential.
   - Rollover trade records are tagged with `isRollover = true` in SQLite to prevent distorting strategy win-rate statistics.
4. **And** unit test `CmeAutoRolloverManagerTest.java` passes, verifying volume-crossover detection, spread combo order formation, and position ticket price adjustments.

## Tasks / Subtasks

- [ ] **Task 1: Expiration Calendar & Volume Monitor (`trading-core`, `trading-broker`)** (AC: 1, 2)
  - [ ] Implement `CmeContractExpiryCalculator.isRollWindow(String symbol, Instant currentTime)`.
  - [ ] Poll volume data for front-month vs next-month contracts during roll window.
  - [ ] Trigger roll condition when `nextMonthVolume > frontMonthVolume` for 2 consecutive periods.
- [ ] **Task 2: CME Calendar Spread Order Formulation (`trading-broker`, `com.ib.client`)** (AC: 3)
  - [ ] Construct IBKR `ComboContract` with leg 1 (Sell Front) and leg 2 (Buy Next).
  - [ ] Submit market-spread order via `EClientSocket.placeOrder()`.
- [ ] **Task 3: Position State & Stop Migration (`trading-core`, `trading-data`)** (AC: 3)
  - [ ] Update active position symbol and calculate new cost basis based on spread fill price.
  - [ ] Re-issue bracket stop orders for the new contract month.
- [ ] **Task 4: Unit & Simulation Tests (`trading-core`, `trading-broker`)** (AC: 4)
  - [ ] Author `CmeAutoRolloverManagerTest.java` with simulated March -> June roll sequence.
