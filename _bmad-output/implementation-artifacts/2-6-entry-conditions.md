# Story 2.6: Entry Conditions

Status: done

## Story

As a developer,
I want a boolean condition evaluator over SQ `Item` trees that wires Signal slots to IfThen entry rules,
so that parsed strategies can answer “is long/short entry true on this bar?” using existing indicator math.

## Acceptance Criteria

1. **AC1 — Condition evaluator:** `SqConditionEvaluator.evaluate(SqXmlItem, SqEvaluationContext)` returns `Optional<Boolean>` for `returnType=boolean` items.
2. **AC2 — Boolean operators:** Supports `AND`, `Not`, `Boolean` (config param `#Value#`), `BooleanVariable` (signal UUID in `#Variable#`).
3. **AC3 — Numeric compare:** Supports at least `IsGreater` / `IsLower` (or SQ equivalents) when left/right `#Indicator#` blocks resolve via `SqIndicatorRegistry`; unknown indicators → `Optional.empty()`.
4. **AC4 — Signal pass:** `SqSignalEvaluator` evaluates the `OnBarUpdate` / `type=Signal` rule trees and produces `Map<String, Boolean>` keyed by signal variable UUID.
5. **AC5 — Entry gate:** `SqEntryEvaluator` evaluates `IfThen` rules named like entry hooks (`Long entry`, `Short entry`, or `LongEntrySignal` / `ShortEntrySignal` variable refs) and exposes `longEntryActive()` / `shortEntryActive()` for the current bar.
6. **AC6 — Context:** `SqEvaluationContext` holds `List<Bar>`, `StrategyConfig`, and the signal result map (for `BooleanVariable` lookups).
7. **AC7 — Tests:** Unit tests for boolean ops, signal→entry wiring (fixture fragments from `strategy-1.6.221B.xml`), registry-backed compare; `mvn test -pl trading-parser` green.
8. **AC8 — Docs:** Update `docs/sq-xml-format.md` §6 to mark 2-6 scope and defer list for GAP operators.

## Tasks / Subtasks

- [x] Task 1: `SqEvaluationContext` + value resolver for numeric items (AC: 6)
- [x] Task 2: `SqConditionEvaluator` boolean + compare ops (AC: 1, 2, 3)
- [x] Task 3: `SqSignalEvaluator` + `SqEntryEvaluator` (AC: 4, 5)
- [x] Task 4: Tests + docs (AC: 7, 8)

## Dev Agent Record

### File List

- `trading-parser/src/main/java/com/martinfou/trading/parser/conditions/SqBlockUtils.java`
- `trading-parser/src/main/java/com/martinfou/trading/parser/conditions/SqEvaluationContext.java`
- `trading-parser/src/main/java/com/martinfou/trading/parser/conditions/SqValueEvaluator.java`
- `trading-parser/src/main/java/com/martinfou/trading/parser/conditions/SqConditionOperators.java`
- `trading-parser/src/main/java/com/martinfou/trading/parser/conditions/SqConditionRegistry.java`
- `trading-parser/src/main/java/com/martinfou/trading/parser/conditions/SqConditionEvaluator.java`
- `trading-parser/src/main/java/com/martinfou/trading/parser/conditions/SqSignalEvaluator.java`
- `trading-parser/src/main/java/com/martinfou/trading/parser/conditions/SqEntryEvaluator.java`
- `trading-parser/src/test/java/com/martinfou/trading/parser/conditions/SqConditionEvaluatorTest.java`
- `trading-parser/src/test/java/com/martinfou/trading/parser/conditions/SqCompareConditionTest.java`
- `trading-parser/src/test/java/com/martinfou/trading/parser/conditions/SqSignalEntryIntegrationTest.java`
- `trading-parser/src/test/java/com/martinfou/trading/parser/conditions/StrategyConfigTestSupport.java`
- `docs/sq-xml-format.md`

## Change Log

- 2026-05-30: Story 2-6 created — entry condition evaluation framework spec.
- 2026-05-30: Implemented condition/signal/entry evaluators with tests.

### Review Findings (2026-05-30)

**Verdict:** ✅ AC1–AC8 satisfaits ; `mvn test -pl trading-parser` OK (62 tests).

#### defer

- [x] [Review][Defer] **Opérateurs bar-history / runtime** [`SqConditionRegistry.DEFERRED`] — `IsFalling`, `IsLowerCount`, `MarketPositionIsLong`, etc. ; documentés dans `sq-xml-format.md` §2-6 defer.
- [x] [Review][Defer] **Heuristique nom règle entry** [`SqEntryEvaluator.java:68-84`] — Détection long/short via `rule.name()` ; suffisant pour fixture ; lier à `LongEntrySignal` UUID en 2-7+ si besoin.
- [x] [Review][Defer] **`OR` non implémenté** — Catalogue utilise surtout `AND` ; ajouter si fixture l’exige.
- [x] [Review][Defer] **Entry sans `EnterAtStop` dans Then** — `Long entry` fixture a `<Then />` vide ; `longEntryActive` = condition If seulement ; actions en 2-8.
- [x] [Review][Defer] **Signaux interdépendants** — Ordre d’évaluation document order ; pas de tri topologique si signaux se référencent entre eux.
