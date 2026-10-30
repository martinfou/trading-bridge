# Story 46.6: Deterministic H1/D1 Bar-Close Scheduler & Multi-Regime Ensemble Runner

Status: review

## Story

As a quantitative strategy operator,
I want a precision scheduler triggering at `:00:05` post bar-close to retrieve newly closed bars from IBKR and evaluate the 3-strategy ensemble (`MES`/`MNQ` Squeeze, `M2K` Pullback, `MGC` Macro Trend),
So that our swing portfolio operates completely hands-free on exact hourly and daily session schedules with zero CPU spin and immediate order dispatch.

## Acceptance Criteria

1. **Given** the `CmeGlobexMarketCalendar` operating in US Eastern Time (`America/New_York`):
   - `BarCloseScheduler.java` schedules wakeups at exactly `:00:05` seconds after each hour during CME open hours (Sunday 18:00 ET to Friday 17:00 ET, excluding the 17:00–18:00 ET daily maintenance window).
   - Daily bar evaluation triggers at `16:00:05 ET` upon regular trading session close.
2. **When** the scheduler wakes up:
   - It queries IBKR via `HistoricalDataService` for the single most recent closed bar for `MES`, `MNQ`, `M2K`, and `MGC`.
   - The bar timestamp is validated to match the expected closed period with no look-ahead.
3. **When** bars are delivered:
   - The scheduler passes the bars to the active multi-regime strategy ensemble:
     - `LtBollingerSqueeze` on `MES` and `MNQ` (H1 timeframe)
     - `LtPullbackEntry` on `M2K` (H1 timeframe)
     - `LtCrossMomentum` on `MGC` (D1 timeframe)
   - Strategy signals are processed through `SmallAccountMarginGuard` and routed to `IbkrBracketOrderRouter`.
4. **And** integration test `BarCloseSchedulerEnsembleTest.java` passes, verifying precision timing, bar ingestion, multi-strategy evaluation, and bracket order emission.

## Tasks / Subtasks

- [x] **Task 1: Precision Bar-Close Scheduler (`trading-runtime`)** (AC: 1, 2) — *completed in 46-7 (`BarCloseScheduler` aligned to `:00:05`, live equity & positions injected, stale bar check enforced)*
  - [x] Implement `BarCloseScheduler.java` using `ScheduledExecutorService` aligned to clock `:00:05`.
  - [x] Validate session open status via `CmeGlobexMarketCalendar.isTradingTime(now)`.
- [x] **Task 2: Bar Retrieval & Look-Ahead Verification (`trading-data`, `trading-runtime`)** (AC: 2) — *completed in 46-9 (`IbkrBarSource` with forming-bar exclusion)*
  - [x] Fetch closed bar via IBKR API (`IbkrBarSource`).
  - [x] Assert bar timestamp + timeframe $\le \text{currentTime}$.
- [ ] **Task 3: Multi-Regime Ensemble Wiring (`trading-strategies`, `trading-runtime`)** (AC: 3) — *deferred per `tech-spec-ibkr-live-futures-lane.md` (MVP runs single strategy `sq-mgc-m30-breakout-0-24542` via `IbkrLiveRunner` in 46-10)*
  - [ ] Configure `LongTermStrategyCatalog` instances for the 4 instruments.
  - [x] Wire evaluation output directly to `IbkrBracketOrderRouter` (`IbkrOrderDispatcher` in 46-10).
- [x] **Task 4: Unit & End-to-End Simulation Tests (`trading-runtime`)** (AC: 4)
  - [x] Author `BarCloseSchedulerEnsembleTest.java` and `BarCloseSchedulerTimingTest.java` verifying full cycle from clock trigger to bracket emission.
