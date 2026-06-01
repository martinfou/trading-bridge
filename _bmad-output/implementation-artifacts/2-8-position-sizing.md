# Story 2.8: Position Sizing and Entry/Exit Actions

Status: done

## Story

As a developer,
I want SQ `EnterAtStop` and `CloseAllPositions` Items parsed into order intents with sizing and SL/PT from Variables,
so that active entry/exit rules produce actionable structures for backtest/codegen (2-9).

## Acceptance Criteria

1. **AC1 — Order intent:** `SqActionParser` parses `EnterAtStop` → `SqOrderIntent` (side, quantity, stop price, SL/PT pips, bars valid).
2. **AC2 — Sizing:** Quantity from `#Size#` / global `MoneyManagement` (`PositionSizingConfig.fixedSizeOr`).
3. **AC3 — Price:** Stop price from `#Price#` formula indicator via `SqValueEvaluator` when supported.
4. **AC4 — Close intent:** `CloseAllPositions` → `SqCloseIntent` (direction, magic number ref).
5. **AC5 — Orchestration:** `SqStrategyActionsEvaluator` combines 2-6/2-7 entry/exit gates with action parsing.
6. **AC6 — Tests:** Parser unit tests + fixture `EnterAtStop`; `mvn test -pl trading-parser` green.
7. **AC7 — Docs:** Update `docs/sq-xml-format.md` §6.

## Tasks / Subtasks

- [x] Task 1: Intent models + sizing/price parsing (AC: 1, 2, 3)
- [x] Task 2: Close intent + strategy actions evaluator (AC: 4, 5)
- [x] Task 3: Tests + docs (AC: 6, 7)

## Dev Agent Record

### File List

- `trading-parser/src/main/java/com/martinfou/trading/parser/actions/SqPositionSizing.java`
- `trading-parser/src/main/java/com/martinfou/trading/parser/actions/SqOrderIntent.java`
- `trading-parser/src/main/java/com/martinfou/trading/parser/actions/SqCloseDirection.java`
- `trading-parser/src/main/java/com/martinfou/trading/parser/actions/SqCloseIntent.java`
- `trading-parser/src/main/java/com/martinfou/trading/parser/actions/SqBarActions.java`
- `trading-parser/src/main/java/com/martinfou/trading/parser/actions/SqActionParser.java`
- `trading-parser/src/main/java/com/martinfou/trading/parser/actions/SqStrategyActionsEvaluator.java`
- `trading-parser/src/test/java/com/martinfou/trading/parser/actions/SqActionParserTest.java`
- `trading-parser/src/test/java/com/martinfou/trading/parser/actions/SqStrategyActionsEvaluatorTest.java`
- `trading-parser/src/test/java/com/martinfou/trading/parser/actions/StrategyConfigTestSupport.java`
- `docs/sq-xml-format.md`

## Change Log

- 2026-05-30: Story 2-8 implemented — EnterAtStop/CloseAllPositions intents + bar evaluator (71 tests green).
- 2026-05-30: Code review — 0 patch, 5 defer, 2 dismissed; status → done.

### Review Findings

- [x] [Review][Defer] `#Size#` variable ref uses `intParameter` — truncates fractional lots (`SqPositionSizing.java:28`); fixture uses `UseGlobalMM` formula path instead.
- [x] [Review][Defer] `SqStrategyActionsEvaluator` re-evaluates IfThen conditions inline — does not delegate to `SqEntryEvaluator`/`SqExitEvaluator` (carried from 2-7).
- [x] [Review][Defer] `SqOrderIntent.toOrder()` omits SL/PT pips and `barsValid` — backtest wiring deferred to 2-9.
- [x] [Review][Defer] EnterAtStop `#MagicNumber#` not captured on intent — runtime lookup in 2-9.
- [x] [Review][Defer] Duplicate `StrategyConfigTestSupport` in `actions` and `conditions` test packages — consolidate when touching tests next.
