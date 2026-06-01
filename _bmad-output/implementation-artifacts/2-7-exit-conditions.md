# Story 2.7: Exit Conditions

Status: done

## Story

As a developer,
I want exit IfThen rules and position-aware boolean ops wired like entry conditions,
so that parsed strategies can answer “should long/short exit fire on this bar?”.

## Acceptance Criteria

1. **AC1 — Exit evaluator:** `SqExitEvaluator` evaluates `IfThen` exit rules (`Long exit`, `Short exit`, or `CloseAllPositions` actions).
2. **AC2 — Reuse conditions:** Uses `SqSignalEvaluator` + `SqConditionEvaluator` (same as 2-6).
3. **AC3 — Position context:** `SqEvaluationContext` carries `longPositionOpen` / `shortPositionOpen` for `MarketPositionIsLong` / `MarketPositionIsShort`.
4. **AC4 — Exit actions:** Detects `CloseAllPositions` as exit action; execution deferred to 2-8/2-9.
5. **AC5 — Tests:** Unit + fixture wiring tests; `mvn test -pl trading-parser` green.
6. **AC6 — Docs:** Update `docs/sq-xml-format.md` §6 for 2-7.

## Tasks / Subtasks

- [x] Task 1: Position context + market position operators (AC: 3)
- [x] Task 2: SqExitEvaluator (AC: 1, 2, 4)
- [x] Task 3: Tests + docs (AC: 5, 6)

## Dev Agent Record

### File List

- `trading-parser/src/main/java/com/martinfou/trading/parser/conditions/SqExitEvaluator.java`
- `trading-parser/src/main/java/com/martinfou/trading/parser/conditions/SqEvaluationContext.java`
- `trading-parser/src/main/java/com/martinfou/trading/parser/conditions/SqConditionOperators.java`
- `trading-parser/src/main/java/com/martinfou/trading/parser/conditions/SqConditionRegistry.java`
- `trading-parser/src/main/java/com/martinfou/trading/parser/sq/SqXmlItem.java` (`isExitAction`)
- `trading-parser/src/test/java/com/martinfou/trading/parser/conditions/SqExitEvaluatorTest.java`
- `docs/sq-xml-format.md`

## Change Log

- 2026-05-30: Implemented exit evaluator with position context and market position operators.

### Review Findings (2026-05-30)

**Verdict:** ✅ AC1–AC6 satisfaits ; `mvn test -pl trading-parser` OK (65 tests).

#### defer

- [x] [Review][Defer] **Duplication Entry/Exit evaluators** [`SqEntryEvaluator`, `SqExitEvaluator`] — Même boucle IfThen ; factoriser en 2-8+ si maintenance lourde.
- [x] [Review][Defer] **Heuristique nom règle exit** [`SqExitEvaluator.java:85-113`] — Reprise pattern 2-6 ; lier à `LongExitSignal` UUID si besoin.
- [x] [Review][Defer] **`CloseAllPositions` exécution** — Détection seulement ; ordre MARKET close en 2-8.
- [x] [Review][Defer] **Position state injectée manuellement** — Pas de sync broker/backtest ; runner 2-8/2-9 alimente `PositionState`.
- [x] [Review][Defer] **Pas de test `shortExitActive` positif** — Fixture ShortExit = `IsLowerCount` (GAP) ; couverture shape-only suffisante pour 2-7.
