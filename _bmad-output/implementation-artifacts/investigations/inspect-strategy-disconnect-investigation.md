# Investigation: inspect-strategy-disconnect

## Hand-off Brief

1. **What happened.** When clicking the "Inspect" strategy button, the Javalin WebSocket disconnects, triggering an unhandled `java.io.IOException: Closed` in `ControlPlaneServer.java:1057` because the socket is closed while streaming the event backlog.
2. **Where the case stands.** The root cause is confirmed: (1) `ControlPlaneServer.onWebSocketConnect` lacks try-catch protection when iterating and calling `ctx.send(...)` for stored backlog events, and (2) `RunEventHub.publish` fails to isolate subscriber exceptions, causing a closed WebSocket connection to throw an exception that bubbles up and can crash/halt the event broadcasting loop (and therefore the strategy execution thread).
3. **What's needed next.** Apply a trivial fix via `bmad-quick-dev` to wrap the event delivery in try-catch blocks in both `ControlPlaneServer.java` and `RunEventHub.java`, and add a unit test in `RunEventHubTest.java`.

## Case Info

| Field            | Value                                                                      |
| ---------------- | -------------------------------------------------------------------------- |
| Ticket           | N/A                                                                        |
| Date opened      | 2026-06-22                                                                 |
| Status           | Concluded                                                                  |
| System           | macOS, Java 21, Javalin / Jetty WebSocket                                  |
| Evidence sources | User log trace showing OANDA streaming disconnect and Javalin WebSocket uncaught exception |

## Problem Statement

The user clicked the inspect button for a strategy in the desktop app, which led to a Javalin/Jetty WebSocket uncaught exception:
`java.io.IOException: org.eclipse.jetty.util.StaticException: Closed` in `ControlPlaneServer.onWebSocketConnect(ControlPlaneServer.java:1057)`.
Prior to this, OANDA pricing stream disconnected and unsubscribed, and a strategy (`InsideBarBreakout`) was stopped.

## Evidence Inventory

| Source   | Status                          | Notes     |
| -------- | ------------------------------- | --------- |
| Log trace | Available | Provided in the user's prompt, showing timestamps, thread info, and the stack trace. |

## Investigation Backlog

| # | Path to Explore | Priority              | Status                                | Notes     |
| - | --------------- | --------------------- | ------------------------------------- | --------- |
| 1 | Examine `ControlPlaneServer.java` | High | Done | Identified line 1057 lacks try-catch protection. |
| 2 | Trace relation to OANDA disconnect | Medium | Done | Determined the exception in `RunEventHub.publish` is critical because it can propagate to threads calling `.append(...)`. |

## Timeline of Events

| Time        | Event               | Source                | Confidence            |
| ----------- | ------------------- | --------------------- | --------------------- |
| 22:00:01.086 | OANDA REST HTTP/2 connection error (GOAWAY received) | log | Confirmed |
| 22:02:17.573 | Unsubscribed from EUR_USD streaming prices | log | Confirmed |
| 22:02:17.574 | OANDA pricing stream disconnected: closed | log | Confirmed |
| 22:02:17.615 | Submitting emergency close order for com.martinfou.trading.core.Order@72f5da1d | log | Confirmed |
| 22:02:17.667 | Closing OANDA trade 1401 | log | Confirmed |
| 22:02:17.799 | OandaStreamingExecutor stopped for InsideBarBreakout on EUR_USD in PAPER mode | log | Confirmed |
| 22:02:32.490 | RunManager fetching last 500 live H1 bars from OANDA | log | Confirmed |
| 22:02:32.597 | RunManager fetching last 500 live H1 bars from OANDA | log | Confirmed |
| 22:02:32.627 | Javalin Uncaught exception in WebSocket handler: IOException: Closed | log | Confirmed |
| 22:02:32.659 | RunManager fetching last 500 live H1 bars from OANDA | log | Confirmed |

## Confirmed Findings

### Finding 1: WebSocket closed during connection handling
**Evidence:** Stack trace showing exception `java.io.IOException: org.eclipse.jetty.util.StaticException: Closed` at `io.javalin.websocket.WsContext.send(WsContext.kt:48)` called by `com.martinfou.trading.runtime.ControlPlaneServer.onWebSocketConnect(ControlPlaneServer.java:1057)`.

**Detail:** The client tried to open a WebSocket connection (presumably triggered by clicking the inspect button), but during `onWebSocketConnect` handler execution, `ctx.send(...)` failed because the socket was already closed or immediately closed.

### Finding 2: Lack of try-catch wrapper in `RunEventHub.publish`
**Evidence:** [RunEventHub.java:L35-L38](file:///Volumes/T7/src/trading-bridge/trading-runtime/src/main/java/com/martinfou/trading/runtime/RunEventHub.java#L35-L38)
```java
        for (Consumer<String> listener : listeners) {
            listener.accept(json);
        }
```

**Detail:** When `listener.accept(json)` calls `ctx::send`, any unchecked `IOException` thrown due to a closed client connection is propagated directly out of the `publish()` call. Because `BroadcastingEventStore.append()` calls `publish()` synchronously inside the execution path of Oanda streaming and strategy executors, this can propagate and crash or halt the execution of active trading runs.

## Deduced Conclusions

### Deduction 1: Client closed connection or server failed send
**Based on:** Finding 1

**Reasoning:** The exception is thrown inside `onWebSocketConnect`. If the server immediately tries to send initial data to the client upon connection, but the client has already disconnected (or aborted), the send operation throws `Closed`.

## Hypothesized Paths

### Hypothesis 1: Immediate websocket send on closed socket
**Status:** Confirmed

**Theory:** In `ControlPlaneServer.java:1057`, the server calls `ctx.send(...)` immediately inside the connect handler. If the client closed/aborted the connection very quickly, or if the write failed, Jetty throws `StaticException: Closed`.

**Supporting indicators:** The stack trace path.

**Resolution:** Confirmed by looking at line 1057 of `ControlPlaneServer.java`, which has an unprotected `ctx.send()` loop.

## Missing Evidence

None. The root cause is fully diagnosed.

## Source Code Trace

| Element       | Detail                                      |
| ------------- | ------------------------------------------- |
| Error origin  | `com.martinfou.trading.runtime.ControlPlaneServer.onWebSocketConnect(ControlPlaneServer.java:1057)` and `com.martinfou.trading.runtime.RunEventHub.publish(RunEventHub.java:36)` |
| Trigger       | Client opens websocket connection (Inspect strategy button) |
| Condition     | Websocket connects but is closed/disconnected when server attempts initial `ctx.send` or subsequent broadcasts |
| Related files | [ControlPlaneServer.java](file:///Volumes/T7/src/trading-bridge/trading-runtime/src/main/java/com/martinfou/trading/runtime/ControlPlaneServer.java), [RunEventHub.java](file:///Volumes/T7/src/trading-bridge/trading-runtime/src/main/java/com/martinfou/trading/runtime/RunEventHub.java) |

## Conclusion

**Confidence:** High

The root cause is a lack of exception isolation in both `ControlPlaneServer`'s initial WebSocket connection logic and `RunEventHub`'s pub-sub broadcasting logic. If a client disconnects quickly, it throws an `IOException` which escapes uncaught.

## Recommended Next Steps

### Fix direction

1. **`ControlPlaneServer.java`**: Wrap the initial event playback loop in a try-catch block. If an exception occurs, log a warning and return early (aborting the subscription).
2. **`RunEventHub.java`**: Wrap `listener.accept(json)` in a try-catch block. If an exception occurs, log a warning and unsubscribe the dead listener so that other subscribers are not affected and the caller thread does not crash.

### Diagnostic

Add a unit test in `RunEventHubTest.java` that registers a throwing subscriber and verifies `publish` completes successfully and delivers to other subscribers.

## Reproduction Plan

1. Open desktop app and click "Inspect" on a strategy.
2. Quickly trigger a refresh or close the inspection tab.
3. Observe `WARN Javalin - Uncaught exception` in `control-plane.log`.
