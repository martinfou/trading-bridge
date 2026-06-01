# Story 2.3: StrategyConfig POJO

Status: done

## Story

As a developer,
I want a `StrategyConfig` POJO that summarizes a parsed SQ strategy for codegen and validation,
so that stories 2-4+ work against stable domain types instead of raw XML trees.

## Acceptance Criteria

1. **AC1 — POJO sections:** `StrategyConfig` exposes version, engine, name, position sizing, global exit defaults, parameters map, signal slots, rules summary, indicator keys, entry actions.
2. **AC2 — From parse tree:** `StrategyConfig.from(SqStrategyDocument)` and `StrategyConfig.parse(InputStream|Path)` build config from parser output.
3. **AC3 — Parameters:** Named lookup (`parameter`, `intParameter`) with typed access for exit vars (`LongStopLoss`, `ShortStopLoss`, …).
4. **AC4 — Rules summary:** Each rule records event, name, type, condition root key, action keys, linked signal variable ids.
5. **AC5 — Indicators deduped:** `indicatorKeys()` unique sorted keys from indicator building blocks.
6. **AC6 — Tests:** Fixture-based tests; existing parser/probe tests remain green.
7. **AC7 — Build:** `mvn test -pl trading-parser` passes.

## Tasks / Subtasks

- [x] Task 1: Config model (AC: 1, 3)
- [x] Task 2: Mapper + parse helpers (AC: 2, 4, 5)
- [x] Task 3: Tests + docs (AC: 6, 7)

## Dev Agent Record

### Agent Model Used

Composer

### Debug Log References

- `mvn test -pl trading-parser` — 27 tests pass

### Completion Notes List

- `StrategyConfig` + nested records in `com.martinfou.trading.parser.config`
- `StrategyConfigMapper` builds summary from `SqStrategyDocument` with deduped indicator/entry keys
- Typed helpers: `intParameter`, `shortStopLossPips`, `exitParameters`, etc.

### File List

- trading-parser/src/main/java/com/martinfou/trading/parser/config/StrategyConfig.java (new)
- trading-parser/src/main/java/com/martinfou/trading/parser/config/StrategyConfigMapper.java (new)
- trading-parser/src/main/java/com/martinfou/trading/parser/config/StrategyParameter.java (new)
- trading-parser/src/main/java/com/martinfou/trading/parser/config/PositionSizingConfig.java (new)
- trading-parser/src/main/java/com/martinfou/trading/parser/config/GlobalExitConfig.java (new)
- trading-parser/src/main/java/com/martinfou/trading/parser/config/SignalSlotConfig.java (new)
- trading-parser/src/main/java/com/martinfou/trading/parser/config/RuleConfig.java (new)
- trading-parser/src/test/java/com/martinfou/trading/parser/config/StrategyConfigTest.java (new)
- docs/sq-xml-format.md

## Change Log

- 2026-05-30: Story 2-3 — StrategyConfig POJO and mapper from SqStrategyDocument

### Review Findings (2026-05-30)

**Verdict:** ✅ AC1–AC7 satisfaits ; `mvn test -pl trading-parser` OK (27 tests).

#### patch (applied)

- [x] [Review][Patch] **`indicatorKeys()` tri alphabétique** [`StrategyConfigMapper.java`] — AC5 exige clés uniques *sorted*.

#### defer

- [x] [Review][Defer] **Symbol / Timeframe absents** — Non présents dans le XML SQ sample ; extraire des params chart en 2-6+ si requis.
- [x] [Review][Defer] **`signalSlots.rootBlockKey`** — Pointe vers le bloc racine (ex. `Boolean`), pas l’indicateur profond ; suffisant pour 2-3 ; enrichir en 2-6.
- [x] [Review][Defer] **Variables homonymes** — Deux `<variable>` même `name` : dernier gagne silencieusement dans la map.
