---
title: 'Strategy Generator Session Windows, Day-of-Week Filters & Pre-Gap Flatten Rules'
type: 'feature'
created: '2026-11-06T13:18:30-05:00'
status: 'done'
baseline_commit: '7b0c7832c0d20384941441eb1d1345a93488b5a4'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/project-context.md'
  - '{project-root}/_bmad-output/forge/strategy-generator-time-day-filters/forged-idea.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** `StrategyGenome` and `StrategyGeneratorService` have no awareness of time-of-day (`09:00–17:00 ET`), day-of-week (`Mon–Fri`), or forced position flattening before overnight (`17:00 ET`) and weekend gaps, leaving generated strategies exposed to overnight/weekend gap risk, rollover spread spikes (`16:00–17:59 ET`), and triple-swaps.

**Approach:** Implement a dual-layer session & gap-protection architecture across `StrategyGenome`, `StrategyGeneratorService`, and `StrategyGeneratorView.vue`: (1) top-level configurable or GA-evolvable session envelopes (`sessionMode`, `entryStartHourEt`, `entryEndHourEt`, `allowedDaysMask`, `exitSessionMode`, `flatCutoffHourEt`) evaluated in `America/New_York` (`ET`) with pre-cutoff bar flattening, and (2) structured GA session/weekday mutations with a `-1.5` fitness complexity penalty and 100% Java codegen parity.

## Boundaries & Constraints

**Always:**
- Evaluate all session hours, weekday checks, and flatten cutoffs in `ZoneId.of("America/New_York")` (`ET`) so DST transitions align with NY RTH, CME maintenance breaks (`17:00–18:00 ET`), and 17:00 ET FX rollover.
- Always call `history.add(bar)` on every trading bar before applying any session/weekday filter so rolling indicators (`EMA`, `SMA`, `RSI`, `ATR`, `Donchian`) remain warm and never stitch across artificial gaps.
- Trigger `INTRADAY_FLAT` and `WEEKEND_FLAT` via `closePosition(bar)` and `clearArmed()` early enough (using `tfMinutes` or `flatCutoffHourEt`, default `16:00 ET`) so `BacktestEngine`'s next-bar-open fill executes *before* the `17:00 ET` break/rollover and Friday weekend close.
- Block new entries and call `clearArmed()` outside allowed weekdays/hours or inside the 60-minute pre-flat entry freeze buffer (`flatCutoffMinutesEt - 60`).
- Bypass intraday hour filters (`entryStartHourEt..entryEndHourEt`) and `INTRADAY_FLAT` on higher timeframes (`H4`, `D1`), applying only `allowedDaysMask` and `WEEKEND_FLAT`.
- Maintain 100% execution parity between `StrategyGenome.GenomeExecutableStrategy` and `StrategyGenome.toJavaSourceCode()`, and handle `null` session fields in existing `databank.json` / `promoted.json` entries as unrestricted (`Mon–Fri` mask `31`, `0..24 ET`, `CONTINUOUS`).

**Ask First:**
- Any change to `BacktestEngine` fill semantics or existing indicator formulas.

**Never:**
- Never skip `history.add(bar)` on out-of-session bars.
- Never allow the GA in `AUTO` mode to mutate arbitrary Swiss-cheese weekday bitmasks (restrict GA mutations to structured regimes: `31` Mon–Fri, `15` Mon–Thu, `30` Tue–Fri, `14` Tue–Thu; and structured hour windows: `0..24`, `9..16`, `9..15`, `8..12`, `3..8`, `8..11`).
- Never use `LocalDateTime.now()` or raw UTC hours for NY session/flatten cutoffs.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Intraday 9-to-5 + EOD Flat (`M30`) | `entryStartHourEt=9`, `entryEndHourEt=16`, `exitSessionMode=INTRADAY_FLAT`, `flatCutoffHourEt=16` | Entries allowed `09:00–14:59 ET` (freeze buffer `15:00–15:59 ET`); open trades & armed stops force-closed on the bar reaching `15:30/16:00 ET` so fill occurs before `17:00 ET` break | Disarm `armedSide` immediately outside window |
| Weekend Flat Only (`H1`) | `exitSessionMode=WEEKEND_FLAT`, `flatCutoffHourEt=16`, `allowedDaysMask=31` | Holds overnight Mon–Thu; on Friday `>= 14:00 ET` blocks new entries and at `>= 15:00/16:00 ET` emits `closePosition(bar)` + `clearArmed()` | N/A |
| Higher Timeframe (`D1` / `H4`) | `timeframe=D1`, `entryStartHourEt=9`, `entryEndHourEt=16`, `exitSessionMode=INTRADAY_FLAT` | Intraday hour window and `INTRADAY_FLAT` bypassed so `D1` bars are not starved; `allowedDaysMask` and Friday `WEEKEND_FLAT` still enforced | Auto-upgrade `INTRADAY_FLAT` on `D1` to `WEEKEND_FLAT` or bypass |
| Legacy `databank.json` Deserialization | Persisted JSON candidate with `null` session fields | Defaults to `entryStartHourEt=0`, `entryEndHourEt=24`, `allowedDaysMask=31`, `exitSessionMode="CONTINUOUS"`, `flatCutoffHourEt=16` | Never throw NPE on `null` fields |

</frozen-after-approval>

## Code Map

- `trading-runtime/src/main/java/com/martinfou/trading/runtime/generator/StrategyGenome.java` -- Add backward-compatible session/weekday/flatten fields (`entryStartHourEt`, `entryEndHourEt`, `allowedDaysMask`, `exitSessionMode`, `flatCutoffHourEt`), summary helpers (`sessionSummary()`), `America/New_York` time helpers, `GenomeExecutableStrategy` session/flatten enforcement, and matching `toJavaSourceCode()` codegen.
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/generator/StrategyGeneratorService.java` -- Extend `GeneratorRequest` and `DatabankCandidate` with session/weekday/flatten fields, support `sessionMode` (`CONTINUOUS`, `DAY_9_TO_5`, `NY_RTH`, `LDN_NY_OVERLAP`, `WEEKEND_FLAT`, `CUSTOM`, `AUTO`), structured GA regime mutation/crossover, and `-1.5` fitness complexity penalty when restricted schedules are used.
- `desktop/src/views/StrategyGeneratorView.vue` -- Add Session, Weekday & Gap-Protection controls (one-click session presets, `Start/End Hour ET`, `Mon–Fri` weekday toggles, `Exit / Gap Mode` selector) and display active session/flatten rules in the Databank table and Strategy DNA Inspector.
- `trading-runtime/src/test/java/com/martinfou/trading/runtime/generator/StrategyGenomeSessionTest.java` -- Unit tests covering `America/New_York` intraday window filtering, pre-cutoff `INTRADAY_FLAT` and Friday `WEEKEND_FLAT` execution inside `BacktestEngine`, indicator warmth preservation, `D1` higher-timeframe bypass, legacy `null` defaults, and `toJavaSourceCode()` parity.

## Tasks & Acceptance

**Execution:**
- [x] `trading-runtime/src/main/java/com/martinfou/trading/runtime/generator/StrategyGenome.java` -- Add session/weekday/flatten fields with canonical + legacy convenience constructors, `America/New_York` bar evaluation (`isIntradayTimeframe`, `shouldFlattenPosition`, `isEntrySessionAllowed`), `sessionSummary()` / `exitSummary()` / `indicatorsUsed()` badges, and identical logic in both `GenomeExecutableStrategy.onBar` and `toJavaSourceCode()` -- ensures both runtime and exported Java strategies enforce session windows and pre-gap flattening without indicator starvation.
- [x] `trading-runtime/src/main/java/com/martinfou/trading/runtime/generator/StrategyGeneratorService.java` -- Add session/weekday/flatten fields to `GeneratorRequest` and `DatabankCandidate`, implement structured GA schedule generation/crossover/mutation when `sessionMode` is `AUTO` (or fixed from request), and apply `-1.5` complexity penalty in `evaluateCandidate` -- prevents GA calendar overfitting while persisting session metadata in `databank.json`.
- [x] `desktop/src/views/StrategyGeneratorView.vue` -- Add Session & Gap-Protection configuration strip (presets: `24/5 Continuous`, `9-to-5 Intraday Flat`, `NY RTH`, `London/NY`, `No Weekend Gap`, `GA Auto-Discover`, custom `Start/End Hour ET`, `Mon–Fri` day pills, `exitSessionMode` selector) and surface `sessionSummary` in the Inspector & Databank -- gives the operator full UI control and visibility.
- [x] `trading-runtime/src/test/java/com/martinfou/trading/runtime/generator/StrategyGenomeSessionTest.java` -- Create unit tests verifying all I/O matrix cases (`INTRADAY_FLAT` before `17:00 ET` in `BacktestEngine`, `WEEKEND_FLAT` on Friday, `Mon–Fri` bitmask filtering, `history.add(bar)` warmth, `D1` bypass, and Java codegen output) -- proves zero overnight/weekend gap exposure and backward compatibility.

**Acceptance Criteria:**
- Given an `M30` strategy with `exitSessionMode="INTRADAY_FLAT"` and `flatCutoffHourEt=16`, when replayed through `BacktestEngine` across multi-day bars, then any open trade is closed before `17:00 ET` and zero positions are held across `17:00–18:00 ET` or weekends.
- Given a strategy with `allowedDaysMask=15` (`Mon–Thu`) and `exitSessionMode="WEEKEND_FLAT"`, when replayed through `BacktestEngine`, then zero new entries occur on Fridays and any open trade is closed before Friday `17:00 ET`.
- Given `sessionMode="AUTO"` in `StrategyGeneratorService`, when evolving genomes, then only structured weekday masks (`31`, `15`, `30`, `14`) and structured ET hour windows are produced and restricted genomes incur a `-1.5` fitness complexity penalty.

## Spec Change Log

## Design Notes

Pre-cutoff flatten calculation in `America/New_York` (`ET`) inside `onBar(Bar bar)`:
```java
ZonedDateTime et = bar.timestamp().atZone(NY_ZONE);
int dowBit = 1 << (et.getDayOfWeek().getValue() - 1); // Mon=1, Tue=2, Wed=4, Thu=8, Fri=16
int barEndMinEt = et.getHour() * 60 + et.getMinute() + tfMinutes;
int cutoffMinEt = Math.clamp(resolvedFlatCutoffHourEt(), 12, 16) * 60;
boolean eodFlatNow = intradayTf && "INTRADAY_FLAT".equalsIgnoreCase(mode) && barEndMinEt >= (cutoffMinEt - tfMinutes);
boolean friFlatNow = ("INTRADAY_FLAT".equalsIgnoreCase(mode) || "WEEKEND_FLAT".equalsIgnoreCase(mode))
    && et.getDayOfWeek() == DayOfWeek.FRIDAY && (!intradayTf || barEndMinEt >= (cutoffMinEt - tfMinutes));
```

## Verification

**Commands:**
- `mvn test -pl trading-runtime -Dtest=StrategyGenomeSessionTest,WfaManagerTest` -- expected: `BUILD SUCCESS`, 0 failures
- `npm --prefix desktop run build` -- expected: TypeScript + Vite production build succeeds with 0 errors

## Suggested Review Order

**Session & Pre-Gap Flatten Engine (`America/New_York` ET)**

- Entry point: pre-cutoff bar flatten logic for daily (`17:00 ET`) and Friday weekend gaps.
  [`StrategyGenome.java:142`](../../trading-runtime/src/main/java/com/martinfou/trading/runtime/generator/StrategyGenome.java#L142)

- DST-aware weekday bitmask, intraday hour window (`startH..endH`), and pre-flat entry freeze gate.
  [`StrategyGenome.java:181`](../../trading-runtime/src/main/java/com/martinfou/trading/runtime/generator/StrategyGenome.java#L181)

- Runtime `GenomeExecutableStrategy.onBar` keeps indicators warm while flattening positions and disarming pending stops.
  [`StrategyGenome.java:1106`](../../trading-runtime/src/main/java/com/martinfou/trading/runtime/generator/StrategyGenome.java#L1106)

- 100% parity standalone Java codegen emitting `NY_ZONE`, `shouldFlattenBeforeGap`, and `isEntryAllowedAt`.
  [`StrategyGenome.java:637`](../../trading-runtime/src/main/java/com/martinfou/trading/runtime/generator/StrategyGenome.java#L637)

**Evolutionary Search & Anti-Overfit Guardrails**

- Structured weekday/session sampling in `AUTO` mode to prevent Swiss-cheese calendar curve-fitting.
  [`StrategyGeneratorService.java:1243`](../../trading-runtime/src/main/java/com/martinfou/trading/runtime/generator/StrategyGeneratorService.java#L1243)

- Crossover and mutation of structured session envelopes with null-safe parent resolution.
  [`StrategyGeneratorService.java:1425`](../../trading-runtime/src/main/java/com/martinfou/trading/runtime/generator/StrategyGeneratorService.java#L1425)

- `-1.5` evolutionary fitness complexity penalty when `AUTO` schedule discovery uses a restricted envelope.
  [`StrategyGeneratorService.java:1016`](../../trading-runtime/src/main/java/com/martinfou/trading/runtime/generator/StrategyGeneratorService.java#L1016)

**Studio UI Presets, Weekday Toggles & Inspector DNA**

- Reactive session presets (`24/5 Hold`, `9-to-5 Flat`, `NY RTH`, `Ldn/NY`, `No Wknd Gap`, `GA Auto`).
  [`StrategyGeneratorView.vue:108`](../../desktop/src/views/StrategyGeneratorView.vue#L108)

- Card 2 Session Hours, `Mon–Fri` bitmask pills, `Start/End ET`, and `Gap Exit Policy` controls.
  [`StrategyGeneratorView.vue:1414`](../../desktop/src/views/StrategyGeneratorView.vue#L1414)

- Strategy DNA Inspector rule card displaying the active `America/New_York` session and gap-protection envelope.
  [`StrategyGeneratorView.vue:1950`](../../desktop/src/views/StrategyGeneratorView.vue#L1950)

**Verification & Backtest Replay Tests**

- Multi-day `BacktestEngine` replay tests for `INTRADAY_FLAT`, `WEEKEND_FLAT`, `D1` bypass, and `STOP` disarming.
  [`StrategyGenomeSessionTest.java:49`](../../trading-runtime/src/test/java/com/martinfou/trading/runtime/generator/StrategyGenomeSessionTest.java#L49)

