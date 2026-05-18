# Story 2.10: UTC Timezone Migration

Status: ready-for-dev

<!-- Validation optional: bmad-create-story validate before bmad-dev-story -->

## Story

As a developer,
I want all timestamps stored and compared in UTC with a documented display timezone,
so that backtest, live OANDA data, and economic calendar events align without silent offset bugs.

## Acceptance Criteria

1. **AC1 — Spec compliance:** Implementation matches [docs/specs.md §2.5](docs/specs.md) (UTC canonical, `America/Toronto` display only).
2. **AC2 — OANDA boundary:** `OandaPriceClient` parses API timestamps to `Instant` (UTC), not naive `LocalDateTime`.
3. **AC3 — Economic calendar:** `EconomicCalendar` events use `Instant` UTC; source publication zones documented in code comments where converted.
4. **AC4 — Domain model:** `Bar`, `Order`, and `Trade` use `Instant` for timestamp fields (or a single `TimeConventions` helper if phased — full migration required for this story).
5. **AC5 — CSV loaders:** `DataLoader` documents assumed CSV timezone; converts to UTC when building `Bar` instances.
6. **AC6 — Live/strategy code:** `AutoTrader`, `StrategyRunner`, `WeekStrategies`, `NewsTradingStrategy` use `Instant` or `Clock` (UTC), not `LocalDateTime.now()`.
7. **AC7 — Build:** `mvn clean install` succeeds; existing tests updated or extended for timezone behavior.

## Tasks / Subtasks

- [ ] Task 1: Introduce time utilities (AC: 1, 7)
  - [ ] Add `com.martinfou.trading.core.TimeConventions` (or `time` package): `UTC`, `DISPLAY_ZONE = America/Toronto`, `Clock.systemUTC()` factory
  - [ ] Helpers: `parseOandaTimestamp(String)`, `toDisplayString(Instant)`
- [ ] Task 2: Migrate core domain (AC: 4, 5, 7)
  - [ ] `Bar.timestamp()` → `Instant`
  - [ ] `Order` createdAt / filledAt → `Instant`
  - [ ] `Trade` entryTime / exitTime → `Instant`
  - [ ] Update `DataLoader`, `BacktestEngine`, `BacktestResult`, `SmaCrossoverStrategy`, `RunBacktest`
- [ ] Task 3: Migrate trading-data (AC: 2, 3, 7)
  - [ ] `OandaPriceClient` → `Instant` on candles/prices
  - [ ] `EconomicCalendar.Event.time` → `Instant`; convert hardcoded events from documented source zones to UTC
  - [ ] `printWeek()` displays in `America/Toronto` with UTC noted in header
- [ ] Task 4: Migrate trading-strategies (AC: 6, 7)
  - [ ] `NewsTradingStrategy`, `WeekStrategies`, `AutoTrader`, `StrategyRunner`
- [ ] Task 5: Docs and agent context (AC: 1)
  - [ ] Confirm `docs/specs.md` §2.5 and `project-context.md` match implementation
  - [ ] Update `docs/conversion-guide.md` JForex time row if types changed

## Dev Notes

- **Priority:** P0 — do before or in parallel with Story 2.1; news/calendar strategies depend on correct event times.
- **Breaking change:** `Bar`/`Order`/`Trade` API change ripples to all modules; compile-fix entire reactor in one PR.
- **Economic calendar:** Current constants mix local release times (CNY, JPY, GBP, USD). Document each event’s source zone, convert to UTC once at definition or load time.
- **OANDA:** v3 candle `time` field is UTC (RFC3339); preserve offset when parsing.
- **Legacy:** Until migration complete, do not add new `LocalDateTime` in trading code paths.

### Project Structure Notes

| Module | Files |
|--------|--------|
| `trading-core` | `Bar`, `Order`, `Trade`, `DataLoader`, new `TimeConventions` |
| `trading-backtest` | `BacktestEngine`, `BacktestResult` |
| `trading-data` | `OandaPriceClient`, `EconomicCalendar` |
| `trading-strategies` | `AutoTrader`, `StrategyRunner`, `WeekStrategies`, `NewsTradingStrategy` |
| `trading-examples` | `RunBacktest`, `SmaCrossoverStrategy` |

### References

- [Source: docs/specs.md §2.5 — Conventions temporelles]
- [Source: _bmad-output/project-context.md — Time rules]
- [Source: docs/conversion-guide.md — Gestion du temps]

## Dev Agent Record

### Agent Model Used

_(to be filled by dev agent)_

### Debug Log References

### Completion Notes List

### File List
