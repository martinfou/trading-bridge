# Story 12.2: Unified Historical Data Loading

Status: done

## Story

As a developer,
I want one `HistoricalDataLoader` API used by all backtest entry points,
so that CSV, Dukascopy, and `.bars` formats resolve consistently.

## Acceptance Criteria

1. **AC1 — Single loader:** All runners use `HistoricalDataLoader` (Dukascopy CSV preferred, `.bars` fallback).
2. **AC2 — Millis canonical:** `BarStore.write()` and `download-data.sh` both store epoch millis.
3. **AC3 — No fabricated timestamps:** `BatchStrategyRunner` does not invent timestamps from row index.
4. **AC4 — Tests:** Unit tests for millis roundtrip and path loading; golden backtest still passes.

## Tasks / Subtasks

- [x] Task 1: Fix download script + document millis (AC: 2)
- [x] Task 2: Enhance HistoricalDataLoader as single entry (AC: 1)
- [x] Task 3: Wire RunBacktest and BatchStrategyRunner (AC: 1, 3)
- [x] Task 4: Add BarStore/HistoricalDataLoader tests (AC: 4)
- [x] Task 5: Verify build + golden test (AC: 4)

## Dev Agent Record

### Agent Model Used

Composer

### Completion Notes List

- `download-data.sh` writes epoch millis to `.bars` (removed `// 1000`)
- `HistoricalDataLoader.loadFile()`, accurate source reporting, `loadYearRange` no longer requires `.bars` when CSV exists
- `RunBacktest` and `BatchStrategyRunner` use `HistoricalDataLoader`; removed fabricated CSV timestamps
- Added `BarStoreTest`, `HistoricalDataLoaderTest`; `mvn clean install` green; golden test passes

### File List

- `scripts/download-data.sh`
- `trading-data/src/main/java/com/martinfou/trading/data/HistoricalDataLoader.java`
- `trading-data/src/main/java/com/martinfou/trading/data/BarStore.java`
- `trading-data/src/test/java/com/martinfou/trading/data/BarStoreTest.java`
- `trading-data/src/test/java/com/martinfou/trading/data/HistoricalDataLoaderTest.java`
- `trading-genetics/pom.xml`
- `trading-genetics/src/main/java/com/martinfou/trading/genetics/BatchStrategyRunner.java`
- `trading-examples/src/main/java/com/martinfou/trading/examples/RunBacktest.java`
- `docs/testing.md`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`

## Change Log

- 2026-05-23: Story 12.2 — unified historical data loading

### Review Findings

- [x] [Review][Patch] Fichiers tests + story non suivis — `BarStoreTest.java`, `HistoricalDataLoaderTest.java`, `12-2-unified-historical-data-loading.md` en `??`
- [x] [Review][Defer] `.bars` existants en secondes vs script millis — lecture legacy OK, re-download documenté dans `docs/testing.md` — deferred, by design
- [x] [Review][Defer] `BatchStrategyRunner` n'expose pas encore `SYMBOL YEAR` via `loadFromArgs` — hors scope 12.2, CLI unifiée en 12.3 — deferred
