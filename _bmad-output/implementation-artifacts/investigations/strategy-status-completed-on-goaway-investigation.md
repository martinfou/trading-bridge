# Investigation: Strategy Status Turns COMPLETED on HTTP/2 GOAWAY

## Hand-off Brief

1. **What happened.** A transient HTTP/2 `GOAWAY` error from OANDA's REST API (during a `fetchAccountSummary` call inside `checkRiskCircuitBreakers`) propagates as an uncaught exception through `processTick`, which catches it, emits an error event, then asynchronously calls `this::stop`. `stop()` calls `emitEnded()` — which appends a `RUN_ENDED` event to the event store — and then returns normally. The `executeRun` loop in `RunManager` sees the executor went inactive, calls `oandaExecutor.stop()` again (harmless), then hits `requireTerminalEvent(RUN_ENDED)` which succeeds (the event is already there), and since the record is still `RUNNING` it calls `markCompleted`, producing status `COMPLETED` instead of `FAILED`.
2. **Where the case stands.** Root cause is **Confirmed** (High confidence). The normal stop path and the error stop path are indistinguishable because `stop()` always emits `RUN_ENDED` and returns cleanly.
3. **What's needed next.** Implement `bmad-quick-dev`: have `processTick`'s exception handler call a dedicated `stopWithError(cause)` path that marks the record `FAILED` (or propagates a flag so `RunManager` can do it), instead of reusing the normal `stop()` path.

## Case Info

| Field            | Value                                                                 |
| ---------------- | --------------------------------------------------------------------- |
| Ticket           | N/A                                                                   |
| Date opened      | 2026-06-15                                                            |
| Status           | Concluded                                                             |
| System           | macOS, Java 21 virtual threads, OANDA practice API (HTTP/2)           |
| Evidence sources | Stack trace (user-provided), RunManager.java, OandaStreamingExecutor.java |

## Problem Statement

Strategies running in PAPER mode via `OandaStreamingExecutor` transition to status `COMPLETED` even though the user did not stop them. The user observed three strategies showing `COMPLETED` on the Trading Desk UI (screenshot) after no explicit stop action.

## Evidence Inventory

| Source                            | Status    | Notes                                                              |
| --------------------------------- | --------- | ------------------------------------------------------------------ |
| Stack trace (user-provided)       | Available | Two runs affected (`dd4f687e`, `b6d13477`), same exception pattern |
| `RunManager.java` (lines 450–517) | Available | `executeRun` loop and completion/failure handling                  |
| `OandaStreamingExecutor.java`     | Available | `processTick`, `stop`, `emitEnded`, `checkRiskCircuitBreakers`     |
| `RunRecord.java`                  | Available | Status enum and mutation methods                                   |
| Application logs                  | Partial   | Provided by user; no WARN-level "Run failed" entry from RunManager |

## Investigation Backlog

| # | Path to Explore                                         | Priority | Status | Notes |
|---|---------------------------------------------------------|----------|--------|-------|
| 1 | `OandaStreamingExecutor.processTick` error handler      | High     | Done   | See Finding 1 |
| 2 | `RunManager.executeRun` post-executor-stop logic        | High     | Done   | See Finding 2 |
| 3 | `stop()` / `emitEnded()` always-emit behaviour          | High     | Done   | See Finding 3 |
| 4 | `requireTerminalEvent` gating behaviour                 | Medium   | Done   | See Deduction 1 |
| 5 | Whether `markFailed` is ever called for network errors  | High     | Done   | See Finding 4 |

## Timeline of Events

| Time (UTC approx.)  | Event                                                                          | Source        | Confidence |
| ------------------- | ------------------------------------------------------------------------------ | ------------- | ---------- |
| 09:07:12            | `OandaStreamingExecutor` started for `Harness_BuyThenCloseNextBar`             | Log           | Confirmed  |
| 09:17:52            | One `OandaStreamingClient` unsubscribed (likely a separate restart or stop)    | Log           | Confirmed  |
| 11:30:11            | Run `dd4f687e` — `checkRiskCircuitBreakers` → `fetchAccountSummary` → GOAWAY   | Stack trace   | Confirmed  |
| 11:30:11            | `processTick` catch block fires; error event emitted; `Thread.ofVirtual().start(this::stop)` | Source:272-276 | Confirmed |
| 11:30:11            | `stop()` called → `emitEnded()` appends `RUN_ENDED` → record still `RUNNING`  | Source:162-178 | Confirmed |
| 11:30:11            | `OandaStreamingClient` unsubscribed; executor reports stopped for `InsideBarBreakout` | Log    | Confirmed  |
| ~11:30:11           | `RunManager.executeRun` detects `!oandaExecutor.isActive()`, exits loop        | Source:457-460 | Confirmed |
| ~11:30:11           | `requireTerminalEvent(RUN_ENDED)` passes; `record.status() == RUNNING` → `markCompleted` | Source:499-503 | Confirmed |
| 11:39:22            | Same pattern for run `b6d13477` (`Harness_BuyThenCloseNextBar`)                | Stack trace   | Confirmed  |

## Confirmed Findings

### Finding 1: `processTick` catches all exceptions and calls `stop()` asynchronously

**Evidence:** `OandaStreamingExecutor.java:272-276`

```java
} catch (Exception e) {
    log.error("Run {} failed during tick processing", runId, e);
    RunEvent errorEvent = RunEvent.error(...);
    eventStore.append(runId, errorEvent);
    Thread.ofVirtual().start(this::stop);  // ← calls normal stop
}
```

**Detail:** Any exception during tick processing — including a transient network error like `GOAWAY` — lands here. The code correctly logs and emits an error event, but then delegates to `this::stop`, the **same path used for a normal operator-initiated stop**.

### Finding 2: `stop()` always calls `emitEnded()` regardless of the reason for stopping

**Evidence:** `OandaStreamingExecutor.java:162-178`

```java
public void stop() {
    if (!active.getAndSet(false)) { ... return; }
    try {
        streamingClient.removeListener(tickListener);
        streamingClient.unsubscribe(config.symbol());
        broker.disconnect();
        emitEnded();   // ← always appended
        log.info("OandaStreamingExecutor stopped ...");
    } finally {
        stopLatch.countDown();
    }
}
```

**Detail:** `emitEnded()` appends a `RUN_ENDED` event (`RunEvent.ended(...)`) to the event store unconditionally. There is no "error stop" variant.

### Finding 3: `RunManager.executeRun` marks the run COMPLETED if status is still RUNNING after the loop

**Evidence:** `RunManager.java:499-503`

```java
requireTerminalEvent(runId, RunEventType.RUN_ENDED);  // passes because stop() emitted it
if (record.status() == RunRecord.Status.RUNNING) {
    record.markCompleted(BacktestResultPayload.toEndedPayload(result));
    notifyTransition(before, record, RunTransition.COMPLETE);
}
```

**Detail:** The gate (`requireTerminalEvent`) passes because `stop()` always emits `RUN_ENDED`. Since the record was never transitioned to `FAILED` before reaching this point, `status() == RUNNING` is `true`, and `markCompleted` is called → status becomes `COMPLETED`.

### Finding 4: `RunManager`'s `catch (RuntimeException e)` block is never reached for this failure path

**Evidence:** `RunManager.java:507-516`

```java
} catch (RuntimeException e) {
    ...
    if (record.status() == RunRecord.Status.RUNNING ...) {
        record.markFailed(msg);
        notifyTransition(before, record, RunTransition.FAIL);
    }
}
```

**Detail:** The exception from `processTick` is caught *inside* `OandaStreamingExecutor` (Finding 1) and does not propagate to `RunManager`'s executor thread. The `catch` block in `RunManager` is never triggered for this failure mode.

## Deduced Conclusions

### Deduction 1: The normal and error stop paths are indistinguishable to `RunManager`

**Based on:** Findings 1, 2, 3

**Reasoning:** `stop()` is called whether the stop is user-initiated or error-driven. It always emits `RUN_ENDED`. `RunManager` only checks `isActive()` and the `RUN_ENDED` event — it has no signal that the stop was forced by an error.

**Conclusion:** `RunManager` cannot distinguish an operator stop from an error-driven stop, so it always concludes with `COMPLETED`.

## Hypothesized Paths

### Hypothesis 1: The `GOAWAY` is a transient infrastructure blip, not an API credential issue

**Status:** Open

**Theory:** The GOAWAY error happens at 11:30 AM and 11:39 AM, hours after a clean start at 09:07. This is consistent with an HTTP/2 connection being recycled by a load balancer or proxy (common in cloud infrastructure), rather than an authentication failure.

**Supporting indicators:** The stream was active for over 2 hours before failing; only the REST client (account summary) fails — not the streaming connection.

**Would confirm:** A second run of the same strategy that survives past that time window without errors.

**Would refute:** Repeated failures immediately on restart, or an `HTTP 401` / `HTTP 403` in the REST response.

**Resolution:** Open — not relevant to the COMPLETED bug root cause, but relevant to resilience.

## Missing Evidence

| Gap                                  | Impact                                                        | How to Obtain                                   |
| ------------------------------------ | ------------------------------------------------------------- | ----------------------------------------------- |
| `RunManager` logs around 11:30-11:39 | Would confirm no `markFailed` was called                      | Full application log from that session           |
| HTTP response code for the GOAWAY    | Would confirm transient vs. auth failure (Hypothesis 1)       | Enable HTTP/2 debug logging or OANDA audit log   |

## Source Code Trace

| Element       | Detail                                                                                 |
| ------------- | -------------------------------------------------------------------------------------- |
| Error origin  | `HttpOandaRestClient.java:274` — `fetchAccountSummary` throws `IllegalStateException`  |
| Trigger       | `OandaStreamingExecutor.java:319` — `checkRiskCircuitBreakers` on every tick           |
| Condition     | HTTP/2 GOAWAY received on the REST `HttpClient` connection                             |
| Related files | `OandaStreamingExecutor.java:272-276` (catch), `:162-178` (stop), `RunManager.java:499-503` (markCompleted) |

## Conclusion

**Confidence:** High

The root cause is a **missing error-stop code path**. When `processTick` catches an exception it calls the normal `stop()` method, which emits `RUN_ENDED` and returns cleanly. `RunManager`'s loop sees `!isActive()`, finds `RUN_ENDED` in the event store, and — because the record is still `RUNNING` — calls `markCompleted`. No path calls `markFailed`. The `IllegalStateException: Failed to fetch OANDA account summary` (itself caused by a transient HTTP/2 `GOAWAY`) is the proximate trigger, but the design flaw is that error-driven stops and operator-initiated stops share the same code path.

## Recommended Next Steps

### Fix direction

Two changes are needed:

1. **`OandaStreamingExecutor` — add a `stopWithError(Throwable cause)` method** (or a boolean `boolean errorStop` flag) that emits a `RUN_ERROR` or annotated `RUN_ENDED` event so `RunManager` knows the stop was fault-driven.

   **Simpler alternative:** Have the error handler in `processTick` directly call `record.markFailed(...)` before calling `stop()`, since `RunRecord` fields are `volatile` and `RunManager` checks the record status after the loop exits.

2. **`checkRiskCircuitBreakers` — add a try/catch and retry for transient `IOException` / HTTP errors.** The `GOAWAY` is almost certainly a transient connection reset. The strategy should not die on a single failed account summary fetch; it should retry or skip that check for that tick.

### Diagnostic

- Add a debug log line in `RunManager.executeRun` after the while-loop exits to log the record status: `log.debug("Run {} exited loop with status {}", runId, record.status())`.
- Add retry logic (up to 3 attempts, 500 ms backoff) in `HttpOandaRestClient.get()` for `IOException` with "GOAWAY" in the message.

## Reproduction Plan

1. Start a PAPER run with any strategy.
2. Wait for the run to be `RUNNING` with active streaming.
3. From the server side (or a proxy), force-close the HTTP/2 connection to `api-fxpractice.oanda.com` (e.g., using `tc` or a network interceptor).
4. Observe: the run transitions to `COMPLETED` (bug) instead of `FAILED` (expected).
