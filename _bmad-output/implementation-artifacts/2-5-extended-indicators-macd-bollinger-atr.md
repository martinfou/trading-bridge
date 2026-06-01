# Story 2.5: Extended Indicators (MACD, Bollinger, ATR)

Status: done

## Story

As a developer,
I want SQ evaluators for MACD, Bollinger Bands, and ATR with shift and SQ param conventions,
so that catalogue strategies using these building blocks can be evaluated from parsed Item trees.

## Acceptance Criteria

1. **AC1 — Registry:** `SqIndicatorRegistry` maps `ATR`, `BBRange`, `BollingerBands`, `MACD`.
2. **AC2 — ATR:** Honors `#Period#`, `#Shift#`; delegates TR math to `Indicators.atr` on truncated bar window.
3. **AC3 — Bollinger:** `BBRange` returns band width; `BollingerBands` returns middle (SMA); reads `#Period#`, `#Deviation#`, `#Shift#`, `#ComputedFrom#`.
4. **AC4 — MACD:** Returns MACD line (fast EMA − slow EMA); reads `#FastPeriod#`, `#SlowPeriod#`, `#SignalPeriod#`, `#Shift#`, `#ComputedFrom#`.
5. **AC5 — Tests:** Unit tests for each indicator + registry dispatch; parser module tests green.
6. **AC6 — Build:** `mvn test -pl trading-parser` passes.

## Tasks / Subtasks

- [x] Task 1: Extended params (Bollinger, MACD) + ATR shift (AC: 2, 3, 4)
- [x] Task 2: SqExtendedIndicators + registry entries (AC: 1)
- [x] Task 3: Tests + docs (AC: 5, 6)

## Dev Agent Record

### File List

- `trading-parser/src/main/java/com/martinfou/trading/parser/indicators/SqParamReaders.java`
- `trading-parser/src/main/java/com/martinfou/trading/parser/indicators/SqParamLiterals.java`
- `trading-parser/src/main/java/com/martinfou/trading/parser/indicators/SqBollingerParams.java`
- `trading-parser/src/main/java/com/martinfou/trading/parser/indicators/SqMacdParams.java`
- `trading-parser/src/main/java/com/martinfou/trading/parser/indicators/SqExtendedIndicators.java`
- `trading-parser/src/main/java/com/martinfou/trading/parser/indicators/SqIndicatorParams.java` (double resolver)
- `trading-parser/src/main/java/com/martinfou/trading/parser/indicators/SqCoreIndicators.java` (`smaAt`/`emaAt`)
- `trading-parser/src/main/java/com/martinfou/trading/parser/indicators/SqIndicatorRegistry.java`
- `trading-parser/src/test/java/com/martinfou/trading/parser/indicators/SqExtendedIndicatorsTest.java`
- `trading-parser/src/test/java/com/martinfou/trading/parser/indicators/SqIndicatorRegistryTest.java`
- `docs/sq-xml-format.md`

## Change Log

- 2026-05-30: Extended SQ indicator registry with ATR, BBRange, BollingerBands, MACD.

### Review Findings (2026-05-30)

**Verdict:** ✅ AC1–AC6 satisfaits ; `mvn test -pl trading-parser` OK (54 tests).

#### defer

- [x] [Review][Defer] **MACD signal / histogram non exposés** [`SqExtendedIndicators.java:48-58`] — `#SignalPeriod#` parsé mais seule la ligne MACD est retournée ; signal/histogram en 2-6+ si règles SQ l’exigent.
- [x] [Review][Defer] **Pas d’alias registry `BB`** — Catalogue mentionne `BB(period,mult)` ; clé SQ probablement `BollingerBands` ; alias `BB` si fixture le confirme.
- [x] [Review][Defer] **Bollinger upper/lower non exposés** — `BollingerBands` retourne le middle (AC3) ; bandes haute/basse via param `#Line#` ou clés séparées en 2-6+.
- [x] [Review][Defer] **Registry dispatch partiel** — Seul ATR testé via registry ; MACD/BB couverts par `SqExtendedIndicatorsTest` ; tests registry BB/MACD optionnels.
- [x] [Review][Defer] **Shift négatif / period ≤ 0** — Reprise finding 2-4 ; validation params en 2-6+.
