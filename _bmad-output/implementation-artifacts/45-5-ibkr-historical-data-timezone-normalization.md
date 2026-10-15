# Story 45.5: IBKR Historical Data Timezone Normalization (`America/New_York` to UTC)

Status: ready-for-dev

## Story

As a quantitative developer,
I want historical data exports from Interactive Brokers TWS to parse timestamps in US Eastern Time (`America/New_York`) with proper Daylight Saving Time (EDT/EST) conversions,
So that historical bar timestamps align with true UTC and do not suffer from a 4–5 hour time phase shift.

## Acceptance Criteria

1. **Given** `IbkrHistoricalDataLoader.java`:
   - `parseIbkrTime(String timeStr)` accepts an optional `ZoneId sourceZone` parameter, defaulting to `ZoneId.of("America/New_York")` for US electronic futures.
2. **When** parsing a timestamp string like `"20240315 09:30:00"` (EDT, UTC-4):
   - The parsed `Instant` is exactly `2024-03-15T13:30:00Z` (13:30 UTC).
3. **When** parsing a timestamp string during standard time `"20240115 09:30:00"` (EST, UTC-5):
   - The parsed `Instant` is exactly `2024-01-15T14:30:00Z` (14:30 UTC).
4. **And** unit test `IbkrTimezoneNormalizationTest.java` validates DST transitions and UTC conversion accuracy.

## Tasks / Subtasks

- [ ] **Task 1: Update IbkrHistoricalDataLoader with Timezone Support (`trading-data`)** (AC: 1, 2, 3)
  - [ ] Update `parseIbkrTime` and `loadCsv` in `IbkrHistoricalDataLoader.java` to support `ZoneId` mapping.
- [ ] **Task 2: Unit Tests (`trading-data`)** (AC: 4)
  - [ ] Author `IbkrTimezoneNormalizationTest.java` covering winter (EST) and summer (EDT) timestamps.
