---
title: 'Fix: strategy status incorrectly set to COMPLETED on transient GOAWAY error'
type: 'bugfix'
created: '2026-06-15'
status: 'done'
route: 'one-shot'
---

# Fix: Strategy Status COMPLETED on Transient GOAWAY Error

## Intent

**Problem:** When a transient HTTP/2 GOAWAY occurs during `checkRiskCircuitBreakers`, the exception propagates through `processTick`, which calls the normal `stop()` path. `stop()` emits `RUN_ENDED`, and `RunManager` then marks the record `COMPLETED` — hiding the failure and preventing proper restart.

**Approach:** (1) In the `processTick` catch block, call `record.markFailed()` before delegating to `stop()`, so `RunManager` reads `FAILED` and skips `markCompleted`. (2) Wrap the equity fetch in `checkRiskCircuitBreakers` with a try/catch so a single bad tick is safely skipped instead of crashing the run.

## Suggested Review Order

1. [`OandaStreamingExecutor.java` — processTick catch block](../../../trading-runtime/src/main/java/com/martinfou/trading/runtime/OandaStreamingExecutor.java) — Fix #1: `markFailed` before `stop()`
2. [`OandaStreamingExecutor.java` — checkRiskCircuitBreakers](../../../trading-runtime/src/main/java/com/martinfou/trading/runtime/OandaStreamingExecutor.java) — Fix #2: transient error handling
3. [`RunManagerTest.java` — startRun_rejectsDuplicateActiveRun](../../../trading-runtime/src/test/java/com/martinfou/trading/runtime/RunManagerTest.java) — Test cleanup race fix
