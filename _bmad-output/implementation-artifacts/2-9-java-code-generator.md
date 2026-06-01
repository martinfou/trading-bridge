# Story 2.9: Java Code Generator from SQ XML

Status: done

## Story

As a developer,
I want StrategyQuant XML converted into compilable `Strategy` Java classes,
so that parsed strategies run in `RunBacktest` without manual JForex export.

## Acceptance Criteria

1. **AC1 — Interpreter strategy:** `SqInterpretedStrategy` implements `Strategy` using the 2-2…2-8 evaluator stack (`SqStrategyActionsEvaluator`).
2. **AC2 — Code generator:** `SqStrategyCodeGenerator` emits compilable Java source (thin wrapper delegating to `SqInterpretedStrategy`).
3. **AC3 — Orders:** Entry intents → `Order.Type.STOP` with SL/PT pips; close intents → MARKET flatten.
4. **AC4 — Position state:** Tracks long/short open for exit rules and `CloseAllPositions`.
5. **AC5 — Tests:** Generator + interpreter unit tests; fixture XML; `mvn test -pl trading-parser` green.
6. **AC6 — Docs:** Update `docs/sq-xml-format.md` §6 for 2-9.

## Tasks / Subtasks

- [x] Task 1: SqInterpretedStrategy runtime (AC: 1, 3, 4)
- [x] Task 2: SqStrategyCodeGenerator (AC: 2)
- [x] Task 3: Tests + docs (AC: 5, 6)

## Dev Agent Record

### File List

- `trading-parser/src/main/java/com/martinfou/trading/parser/codegen/SqPipScale.java`
- `trading-parser/src/main/java/com/martinfou/trading/parser/codegen/SqInterpretedStrategy.java`
- `trading-parser/src/main/java/com/martinfou/trading/parser/codegen/SqCodegenRequest.java`
- `trading-parser/src/main/java/com/martinfou/trading/parser/codegen/SqStrategyCodeGenerator.java`
- `trading-parser/src/test/java/com/martinfou/trading/parser/codegen/SqInterpretedStrategyTest.java`
- `trading-parser/src/test/java/com/martinfou/trading/parser/codegen/SqStrategyCodeGeneratorTest.java`
- `docs/sq-xml-format.md`

## Change Log

- 2026-05-30: Story 2-9 implemented — interpreter runtime + thin wrapper codegen (77 tests green).
- 2026-05-30: Code review — 0 patch, 5 defer, 2 dismissed; status → done.

### Review Findings

- [x] [Review][Defer] Generated wrappers require `trading-parser` on classpath — not suitable for `trading-strategies`-only modules without new dependency.
- [x] [Review][Defer] `StrategyCatalog` / `RunBacktest` integration not wired — use `SqInterpretedStrategy.fromClasspath` directly for now.
- [x] [Review][Defer] Position state set on order queue, not backtest fill — can desync if STOP never fills.
- [x] [Review][Defer] `barsValid` / magic-number filters on `SqCloseIntent` not enforced at runtime.
- [x] [Review][Defer] No `javac` compile test on generated source (unlike `JForexConverterTest`).
