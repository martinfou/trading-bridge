# Story 39.1 — OANDA API 503 and Transient Error Handling during Position Reconciliation

Status: ready-for-dev

Epic: 39 — OANDA API Resilience & Transient Error Handling

## Story

As a trader,
I want position reconciliation to handle transient OANDA API errors (e.g. 503 Service Unavailable) gracefully,
so that transient OANDA API hiccups do not clutter the logs with ERROR level messages or disrupt execution.

## Acceptance Criteria

- [ ] **AC1** — `OandaStreamingExecutor.reconcilePositions` must catch `IllegalStateException` specifically and log a warning instead of a full stack trace ERROR.
- [ ] **AC2** — When a transient `IllegalStateException` occurs, the reconciliation is skipped for the current tick, and the 60-second timer/interval continues normally so that the next tick attempts reconciliation.
- [ ] **AC3** — Programming errors (e.g., NullPointerException) during reconciliation are still caught by the fallback `Exception` catch block and logged as `ERROR`.
- [ ] **AC4** — A unit test in `OandaStreamingExecutorTest.java` is written to verify that when the `Broker` throws `IllegalStateException` during `reconcilePositions`, the executor catches it and skips/continues without throwing or logging an `ERROR`.

## Tasks

- [ ] Modify `OandaStreamingExecutor.java` to add a specific `catch (IllegalStateException e)` block in `reconcilePositions` to log a warning.
- [ ] Ensure that other non-runtime exceptions are still logged as errors in the generic `catch (Exception e)` block.
- [ ] Create `OandaStreamingExecutorTest.java` in `trading-runtime/src/test/java/com/martinfou/trading/runtime/` and write a unit test to verify this behavior under simulated exception conditions.
- [ ] Run `mvn test -pl trading-runtime` to ensure all tests pass.

## Dev Notes

- The same pattern is already used in `checkRiskCircuitBreakers` where `IllegalStateException` from `broker.getAccountState()` is caught and logged as a warning, skipping the tick:
  ```java
  try {
      currentEquity = broker.getAccountState().equity();
  } catch (IllegalStateException e) {
      log.warn("Run {} — transient error fetching account equity; skipping risk check for this tick: {}",
          runId, e.getMessage());
      return;
  }
  ```
- The `IllegalStateException` is thrown by `HttpOandaRestClient.fetchOpenPositions()` when it encounters an API error (e.g., non-200 responses) or connection errors:
  ```java
  throw new IllegalStateException("Failed to fetch OANDA open trades", e);
  ```

## File List

- `trading-runtime/src/main/java/com/martinfou/trading/runtime/OandaStreamingExecutor.java`
- `trading-runtime/src/test/java/com/martinfou/trading/runtime/OandaStreamingExecutorTest.java`

## References

- [OandaStreamingExecutor.java](file:///Volumes/T7/src/trading-bridge/trading-runtime/src/main/java/com/martinfou/trading/runtime/OandaStreamingExecutor.java#L425-L518)
- [HttpOandaRestClient.java](file:///Volumes/T7/src/trading-bridge/trading-data/src/main/java/com/martinfou/trading/data/oanda/HttpOandaRestClient.java#L358-L391)
