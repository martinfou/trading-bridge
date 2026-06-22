---
title: 'Fix: Watchdog Weekend Close and Memory Cleanup'
type: 'bugfix'
created: '2026-06-20'
status: 'draft'
context: ['_bmad-output/project-context.md']
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** When OANDA forex market streams stop sending updates on weekends (Friday 17:00 EST / 22:00 UTC to Sunday 17:00 EST / 22:00 UTC), the `StaleRunWatchdog` flags running strategies as stale, stops them (transitioning them to `COMPLETED`), and attempts restarts. Because the hourly restart throttle resets every hour, this causes a loop that accumulates hundreds of duplicate `COMPLETED` run records in the `RunManager`'s memory, polluting the Trading Desk dashboard and risking memory leaks.

**Approach:** 
1. Implement a `MarketSessionResolver` to determine if a strategy's target market is currently closed based on its symbol and execution label (supporting OANDA Forex and future IBKR stocks/futures).
2. Skip the watchdog's stale checks for any run whose market is closed.
3. Add a thread-safe terminal run pruning mechanism in `RunManager` to evict completed or failed runs older than 10 minutes from memory.

## Boundaries & Constraints

**Always:**
- Keep time calculations using the Eastern Time zone (`America/Toronto`) to simplify standard market hours alignment.
- Use thread-safe removal (`runs.values().removeIf(...)`) when pruning the `runs` map.
- Trigger pruning inside the watchdog's 60-second execution task to avoid overhead on active ticks.

**Ask First:**
- If the user wants a different eviction window than 10 minutes.
- If we should display recently pruned runs in the active summary.

**Never:**
- Hardcode week days/hours without allowing future extensions for other brokers/asset classes.
- Remove running or paused strategies during pruning.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Market Closed (Forex Weekend) | Time is Saturday 12:00 UTC, Symbol = EUR_USD | `MarketSessionResolver.isClosed()` returns true; watchdog skips stale check | N/A |
| Market Open (Forex Weekday) | Time is Tuesday 12:00 UTC, Symbol = EUR_USD | `MarketSessionResolver.isClosed()` returns false; watchdog performs stale check | N/A |
| Stocks Closed (Weekend) | Time is Saturday 12:00 UTC, Symbol = AAPL | `MarketSessionResolver.isClosed()` returns true | N/A |
| Stocks Closed (Weeknight) | Time is Tuesday 02:00 UTC, Symbol = AAPL | `MarketSessionResolver.isClosed()` returns true | N/A |
| Futures Closed (Weekend) | Time is Saturday 20:00 UTC, Symbol = /ES | `MarketSessionResolver.isClosed()` returns true | N/A |
| Run Pruning (Under 10 mins) | Run completed 5 minutes ago | Run remains in `runs` map | N/A |
| Run Pruning (Over 10 mins) | Run completed 11 minutes ago | Run is evicted from `runs` map | N/A |

</frozen-after-approval>

## Code Map

- `trading-runtime/src/main/java/com/martinfou/trading/runtime/MarketSessionResolver.java` -- [NEW] Class to resolve if a market is closed for a given symbol and execution label.
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/StaleRunWatchdog.java` -- Suspend watchdog check if the run's market is closed, and invoke `RunManager.pruneTerminalRuns()`.
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/RunManager.java` -- Add thread-safe terminal run pruning.
- `trading-runtime/src/test/java/com/martinfou/trading/runtime/MarketSessionResolverTest.java` -- [NEW] Unit tests for market session hours.
- `trading-runtime/src/test/java/com/martinfou/trading/runtime/StaleRunWatchdogTest.java` -- Unit tests for watchdog suspension.
- `trading-runtime/src/test/java/com/martinfou/trading/runtime/RunManagerTest.java` -- Unit tests for run pruning.

## Tasks & Acceptance

**Execution:**
- [ ] `trading-runtime/src/main/java/com/martinfou/trading/runtime/MarketSessionResolver.java` -- Implement `isClosed(String symbol, String executionLabel, Instant now)` with logic for Forex (OANDA/IBKR), US Stocks, and Futures.
- [ ] `trading-runtime/src/main/java/com/martinfou/trading/runtime/StaleRunWatchdog.java` -- In `checkStaleRuns()`, skip a run if `MarketSessionResolver.isClosed(symbol, label, now)` is true. Also call `runManager.pruneTerminalRuns()` at the start of the check.
- [ ] `trading-runtime/src/main/java/com/martinfou/trading/runtime/RunManager.java` -- Implement `pruneTerminalRuns()` using `runs.values().removeIf(...)`.
- [ ] `trading-runtime/src/test/java/com/martinfou/trading/runtime/MarketSessionResolverTest.java` -- Implement unit tests testing various weekdays and hours for Forex, Stocks, and Futures.
- [ ] `trading-runtime/src/test/java/com/martinfou/trading/runtime/StaleRunWatchdogTest.java` -- Add test verifying that early exits occur on weekends.
- [ ] `trading-runtime/src/test/java/com/martinfou/trading/runtime/RunManagerTest.java` -- Add test verifying pruning of old completed runs.

**Acceptance Criteria:**
- Given a Forex strategy, when checked on Saturday, then `MarketSessionResolver.isClosed` returns true and it is not restarted.
- Given a completed strategy run, when it has been completed for >10 minutes, then it is removed from `RunManager` memory.

## Design Notes

### Market Hours Logic Example (EST Time Zone)

```java
ZonedDateTime estTime = now.atZone(ZoneId.of("America/Toronto"));
DayOfWeek day = estTime.getDayOfWeek();
int hour = estTime.getHour();
int minute = estTime.getMinute();

// Forex Close: Friday 17:00 to Sunday 17:00 EST
if (day == DayOfWeek.SATURDAY) return true;
if (day == DayOfWeek.FRIDAY && hour >= 17) return true;
if (day == DayOfWeek.SUNDAY && hour < 17) return true;
```

## Verification

**Commands:**
- `mvn test -pl trading-runtime` -- expected: BUILD SUCCESS
