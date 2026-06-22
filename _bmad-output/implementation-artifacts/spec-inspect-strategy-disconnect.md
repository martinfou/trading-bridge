---
title: 'Fix Inspect Strategy WebSocket Disconnect'
type: 'bugfix'
created: '2026-06-22'
status: 'done'
route: 'one-shot'
---

# Fix Inspect Strategy WebSocket Disconnect

## Intent

**Problem:** Clicking the "Inspect" button for a strategy on the desktop application resulted in a WebSocket connection attempt that immediately failed with an unhandled `IOException: Closed` in `ControlPlaneServer.java:1057` while streaming the backlog. Additionally, `RunEventHub.publish` lacked exception isolation, meaning a disconnected client write failure could synchronously propagate and crash the strategy execution thread.

**Approach:** Wrap the event backlog playback loop in `ControlPlaneServer.java` with a try-catch to log a warning and return early if a client disconnects. Wrap the subscriber loop in `RunEventHub.java` with a try-catch to catch any `Throwable`, log a debug message, and unsubscribe dead listeners to protect the calling executor thread. Add a unit test verifying exception isolation and listener unsubscription.

## Suggested Review Order

**WebSocket Backlog Delivery**

- Wrap initial send loop in try-catch to handle client disconnects during backlog replay.
  [`ControlPlaneServer.java:1053-1064`](../../trading-runtime/src/main/java/com/martinfou/trading/runtime/ControlPlaneServer.java#L1053-L1064)

**Event Hub Broadcast**

- Add SLF4J logger imports and static log field to event hub.
  [`RunEventHub.java:3-5`](../../trading-runtime/src/main/java/com/martinfou/trading/runtime/RunEventHub.java#L3-L5)
  [`RunEventHub.java:11-13`](../../trading-runtime/src/main/java/com/martinfou/trading/runtime/RunEventHub.java#L11-L13)

- Wrap subscriber notification in try-catch catching `Throwable`, log at `DEBUG` and unsubscribe dead listeners.
  [`RunEventHub.java:40-47`](../../trading-runtime/src/main/java/com/martinfou/trading/runtime/RunEventHub.java#L40-L47)

**Verification**

- Add unit test to verify that subscriber exceptions are caught and the listener is unsubscribed.
  [`RunEventHubTest.java:49-77`](../../trading-runtime/src/test/java/com/martinfou/trading/runtime/RunEventHubTest.java#L49-L77)
