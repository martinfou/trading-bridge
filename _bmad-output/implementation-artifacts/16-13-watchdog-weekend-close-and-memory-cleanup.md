# Story 16.13: Watchdog Weekend Close and Memory Cleanup

Status: ready-for-dev

## Story

As a trader running strategies continuously on the control plane,
I want the stale run watchdog to suspend checks when the target market is closed (supporting both OANDA Forex and future IBKR stocks/futures hours) and the control plane to prune completed runs from memory,
So that I do not get a massive wall of completed strategies on my Trading Desk dashboard or experience memory leaks when markets are closed.

## Acceptance Criteria

1. **AC1 — Extensible Market Hours Resolver**: Implement a `MarketSessionResolver` (or similar utility class) that determines if the market is open or closed for a given symbol and execution label:
   - **Forex (OANDA/IBKR)**: Closed from **Friday 21:00 UTC** to **Sunday 21:00 UTC**.
   - **US Stocks (future IBKR)**: Open **Monday to Friday 14:30 UTC to 21:00 UTC** (9:30 AM to 4:00 PM EST).
   - **Futures (future IBKR)**: Open from **Sunday 23:00 UTC to Friday 22:00 UTC** (excluding the daily maintenance hour from 17:00 to 18:00 EST / 22:00 to 23:00 UTC).
2. **AC2 — Watchdog Suspension**: In [StaleRunWatchdog.java](file:///Volumes/T7/src/trading-bridge/trading-runtime/src/main/java/com/martinfou/trading/runtime/StaleRunWatchdog.java), check the `MarketSessionResolver` before running `checkStaleRuns` for each run. If the market for the run's symbol/label is closed, log a debug message and skip the stale check for that run.
3. **AC3 — Thread-Safe Memory Pruning**: In [RunManager.java](file:///Volumes/T7/src/trading-bridge/trading-runtime/src/main/java/com/martinfou/trading/runtime/RunManager.java), implement a thread-safe method `pruneTerminalRuns()` to remove completed/failed runs older than **10 minutes** (600 seconds) from the in-memory map.
   - Use thread-safe removal (e.g. `runs.values().removeIf(...)`).
4. **AC4 — Optimized Pruning Trigger**: Trigger the pruning check periodically (e.g., inside the watchdog task every 60 seconds) or when new runs are listed/started to prevent CPU/memory overhead, rather than on every tick.
5. **AC5 — UI Integration**: Ensure that pruned runs are no longer returned in the active runs operational summary (`/api/control/summary`), but are still retrievable via `/api/runs` if persisted.

## Tasks / Subtasks

- [ ] Task 1: Market Session Resolver Implementation (AC: 1)
  - [ ] Create `MarketSessionResolver.java` under `com.martinfou.trading.runtime`.
  - [ ] Implement detection based on symbol patterns (e.g. standard 6-character forex symbols like `EUR_USD`, stock symbols like `AAPL`, and futures formats).
- [ ] Task 2: Suspend Watchdog on Market Close (AC: 2)
  - [ ] Update `checkStaleRuns` in [StaleRunWatchdog.java](file:///Volumes/T7/src/trading-bridge/trading-runtime/src/main/java/com/martinfou/trading/runtime/StaleRunWatchdog.java) to skip a run if `MarketSessionResolver.isClosed(run.symbol(), run.executionLabel())` is true.
- [ ] Task 3: Thread-Safe Pruning in RunManager (AC: 3, 4, 5)
  - [ ] Implement `pruneTerminalRuns()` using `runs.values().removeIf(...)` in [RunManager.java](file:///Volumes/T7/src/trading-bridge/trading-runtime/src/main/java/com/martinfou/trading/runtime/RunManager.java).
  - [ ] Call `runManager.pruneTerminalRuns()` inside the `StaleRunWatchdog` schedule loop (which runs every 60 seconds) to decouple pruning from core tick execution.
- [ ] Task 4: Tests and Verification
  - [ ] Add unit tests in `MarketSessionResolverTest.java` verifying the closed/open state across different days/times for Forex, stocks, and futures.
  - [ ] Add unit tests in [StaleRunWatchdogTest.java](file:///Volumes/T7/src/trading-bridge/trading-runtime/src/test/java/com/martinfou/trading/runtime/StaleRunWatchdogTest.java) to verify watchdog skips stale checks when the resolver returns closed.
  - [ ] Add unit tests in [RunManagerTest.java](file:///Volumes/T7/src/trading-bridge/trading-runtime/src/test/java/com/martinfou/trading/runtime/RunManagerTest.java) verifying thread-safe terminal run pruning.
  - [ ] Run full project build: `mvn clean install`.

## Dev Notes

- **Time Zones**: Use standard UTC calculations for market hours to avoid daylight savings mismatches, or utilize `TimeConventions` if timezone conversion utility is available in `trading-core`.
- **Futures formats**: Futures symbols often start with `/` or contain contract months (e.g., `/ES`, `/NQ`, `ESM6`). Let the resolver classify any symbol starting with `/` or matching standard futures tickers as a futures contract.

### References

- Watchdog: [StaleRunWatchdog.java](file:///Volumes/T7/src/trading-bridge/trading-runtime/src/main/java/com/martinfou/trading/runtime/StaleRunWatchdog.java#L43-L104)
- RunManager: [RunManager.java](file:///Volumes/T7/src/trading-bridge/trading-runtime/src/main/java/com/martinfou/trading/runtime/RunManager.java#L335-L382)
- RunRecord terminal state: [RunRecord.java](file:///Volumes/T7/src/trading-bridge/trading-runtime/src/main/java/com/martinfou/trading/runtime/RunRecord.java#L119-L122)

## Dev Agent Record

### Agent Model Used

Gemini 3.5 Flash

### Debug Log References

### Completion Notes List

### File List
