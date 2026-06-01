# Story 2.4: Core Indicators (SMA, EMA, RSI)

Status: done

## Story

As a developer,
I want SQ building-block evaluators for SMA, EMA, and RSI that honor shift and applied-price params,
so that parsed strategy rules can be evaluated against bar history using shared `Indicators` math.

## Acceptance Criteria

1. **AC1 — Registry:** `SqIndicatorRegistry` maps SQ Item keys `SMA`, `EMA`, `RSI` to evaluators.
2. **AC2 — Params:** Reads `#Period#`, `#Shift#`, `#ComputedFrom#` (applied price) from `SqXmlItem` params; resolves variable references via `StrategyConfig`.
3. **AC3 — Shift:** Evaluation at `bars.size() - 1 - shift` matches SQ bar indexing.
4. **AC4 — Applied price:** Supports at least CLOSE and OPEN on OHLC bars.
5. **AC5 — Delegation:** RSI delegates to `Indicators.rsi`; SMA/EMA use same formulas as core with price field selection.
6. **AC6 — Tests:** Unit tests for params, each indicator, registry dispatch; parser module tests green.
7. **AC7 — Build:** `mvn test -pl trading-parser` passes.

## Tasks / Subtasks

- [x] Task 1: Params + applied price (AC: 2, 4)
- [x] Task 2: Core evaluators + registry (AC: 1, 3, 5)
- [x] Task 3: Tests (AC: 6, 7)

## Dev Agent Record

### File List

- `trading-parser/src/main/java/com/martinfou/trading/parser/indicators/SqAppliedPrice.java`
- `trading-parser/src/main/java/com/martinfou/trading/parser/indicators/SqIndicatorParams.java`
- `trading-parser/src/main/java/com/martinfou/trading/parser/indicators/SqCoreIndicators.java`
- `trading-parser/src/main/java/com/martinfou/trading/parser/indicators/SqIndicatorRegistry.java`
- `trading-parser/src/test/java/com/martinfou/trading/parser/indicators/SqIndicatorParamsTest.java`
- `trading-parser/src/test/java/com/martinfou/trading/parser/indicators/SqCoreIndicatorsTest.java`
- `trading-parser/src/test/java/com/martinfou/trading/parser/indicators/SqIndicatorRegistryTest.java`
- `docs/sq-xml-format.md`

## Change Log

- 2026-05-30: Implemented SQ core indicator layer (SMA, EMA, RSI) with shift/applied-price params and registry dispatch.

### Review Findings (2026-05-30)

**Verdict:** ✅ AC1–AC7 satisfaits ; `mvn test -pl trading-parser` OK (48 tests).

#### defer

- [x] [Review][Defer] **RSI ignore `#ComputedFrom#`** [`SqCoreIndicators.java:39-45`] — Délégation à `Indicators.rsi` (close only) conforme AC5 ; appliqué-price RSI si requis en 2-5+.
- [x] [Review][Defer] **Shift négatif → OOB possible** [`SqIndicatorParams.java:19-21`] — `endIndex` non clampé ; SQ n’émet que shift ≥ 0 via jspinnerVar ; validation params en 2-6+.
- [x] [Review][Defer] **`period` ≤ 0 non gardé** [`SqCoreIndicators.java`] — Division par zéro théorique ; périodes SQ toujours positives dans les fixtures réelles.
- [x] [Review][Defer] **EMA avec shift non testé** — Couverture SMA shift suffisante pour 2-4 ; test EMA shift en 2-5 si besoin.
