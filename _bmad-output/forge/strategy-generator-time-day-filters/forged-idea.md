# Forged Idea: Strategy Generator Session, Day-of-Week & Gap-Avoidance Rules

## Locked Decisions

- **Dual-Layer Architecture (Option C):**
  - **Top-Level Session & Gap-Protection Envelope:** Configurable per generator run (`GeneratorRequest`) and encoded on `StrategyGenome` (`sessionMode`, `entryStartHourEt`, `entryEndHourEt`, `allowedDaysMask`, `exitSessionMode`, `flatCutoffHourEt`, `flatCutoffMinuteEt`).
  - **Evolvable GA Session/Day Regimes:** When session/day mode is `AUTO` (or via `SESSION_TIME_FILTER` / `DAY_OF_WEEK_FILTER` building blocks), the GA mutates across structured microstructure regimes rather than arbitrary bitmasks.
- **Canonical Timezone:** All session windows, weekday checks, and pre-gap flatten cutoffs evaluate in `America/New_York` (`ZoneId.of("America/New_York")`) to align across EST/EDT with NY RTH, CME Globex maintenance (`17:00–18:00 ET`), and 17:00 ET FX rollover/triple-swap.
- **Pre-Cutoff Bar Flatten (Proposal 1):** Because `BacktestEngine` fills market orders emitted by `onBar(previousBar)` at the *next* bar's open (`bar.open()`), `INTRADAY_FLAT` and `WEEKEND_FLAT` trigger `closePosition(bar)` and `clearArmed()` on the penultimate bar before the break (`barTimeMinutesEt + tfMinutes >= cutoffMinutesEt - tfMinutes`, or default `16:00 ET` cutoff) so the exit fills before the `17:00 ET` break/rollover and Friday weekend close.
- **Indicator Warmth vs. Entry/Hold Gating:**
  - `history.add(bar)` ALWAYS executes on every trading bar regardless of session or weekday filters so `EMA`, `SMA`, `RSI`, `ATR`, and `Donchian` never stitch across artificial gaps.
  - Outside the allowed entry window/weekday (or within the 60-minute pre-flat entry freeze buffer), new entries are blocked and any pending stop order (`armedSide`) is immediately cleared via `clearArmed()`.
- **Hybrid UI + Anti-Overfit GA Guardrail (Proposal C):**
  - `StrategyGeneratorView.vue` exposes quick session presets (`24/5 Continuous`, `9-to-5 Intraday Flat`, `NY RTH 09:30–16:00`, `London/NY Overlap`, `No Weekend Hold`, `GA Auto-Discover`) plus explicit `Start/End Hour ET`, `Mon..Fri` toggles, and `exitSessionMode` (`CONTINUOUS`, `INTRADAY_FLAT`, `WEEKEND_FLAT`, `AUTO`).
  - GA mutations are restricted to structured weekday regimes (`MON_FRI`, `MON_THU_NO_FRI`, `TUE_FRI_NO_MON`, `TUE_THU_MIDWEEK`) and session windows (`ALL_HOURS`, `DAY_0900_1600`, `NY_RTH_0930_1530`, `LDN_NY_0800_1200`, `LDN_OPEN_0300_0800`, `NY_MORN_0830_1130`) with a `-1.5` complexity penalty on `evolutionaryFitness` whenever a restricted schedule is active.
- **Higher-Timeframe Guardrail:** Intraday hour filters (`entryStartHourEt..entryEndHourEt`) and `INTRADAY_FLAT` apply only to intraday timeframes (`M5`, `M15`, `M30`, `H1`); `H4` and `D1` automatically bypass intraday hour checks and apply only Day-of-Week filters and `WEEKEND_FLAT`.
- **100% Codegen Parity & Backward Compatibility:** `GenomeExecutableStrategy` and `StrategyGenome.toJavaSourceCode()` emit identical `America/New_York` evaluation logic; `null` session fields in existing `databank.json` / `promoted.json` entries default to unrestricted (`Mon–Fri`, `00:00–24:00 ET`, `CONTINUOUS`).

## Rejected Options & Why

- **Entry-Only Time Filter Without Forced Flatten:** Rejected because trades opened at 14:30 ET remain open through the 17:00 ET rollover spread spike and weekend gaps.
- **Emitting Flatten Order on the Final Pre-Break Bar (`16:30 ET` on M30):** Rejected because `BacktestEngine` fills market orders on the *next* trading bar's open (`18:00 ET` or Sunday `18:00 ET`), which fails to avoid the overnight/weekend gap.
- **Skipping `history.add(bar)` Outside Trading Hours:** Rejected because stitching Friday afternoon directly to Monday morning corrupts rolling indicators (`EMA`, `ATR`, `StdDev`) at session open.
- **Unconstrained 31-Bit Weekday & $24 \times 24$ Hour GA Mutations:** Rejected because on 1–2 year samples with `minTrades = 12–15`, the GA exploits Swiss-cheese schedules to curve-fit out 3–4 in-sample losing trades.
- **Raw UTC Session Hours:** Rejected because US Daylight Saving Time shifts NY RTH, CME maintenance breaks, and the 17:00 ET FX rollover by 1 hour twice a year relative to UTC.

---
*Ready to feed into `bmad-spec`, `bmad-prd`, `bmad-prfaq`, or `bmad-quick-dev`.*

