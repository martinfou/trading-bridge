# Story 12.3: Unified Backtest CLI & Strategy Catalog

Status: done

## Story

As a quant developer,
I want one `RunBacktest` CLI with a registry of all strategy families,
so that I do not need separate runners per strategy source.

## Acceptance Criteria

1. **AC1 — StrategyCatalog:** `com.martinfou.trading.strategies.StrategyCatalog` is the single lookup for strategy IDs across families `prop`, `sqimported`, `generated`, and `examples`. It exposes:
  - `create(String id, String symbol)` → `Strategy`
  - `defaultSymbol(String id)` → `String`
  - `family(String id)` → enum (`PROP`, `SQ_IMPORTED`, `GENERATED`, `EXAMPLE`)
  - `ids()` / `entries()` for listing (includes family + default symbol)
  - `register(...)` plugin hook used by `RunBacktest` at startup for `SmaCrossover` (avoids `trading-strategies` → `trading-examples` dependency)
2. **AC2 — Unified CLI:** `RunBacktest` supports:
  - `RunBacktest --list` — all families, columns: id, family, defaultSymbol
  - `RunBacktest --help`
  - `RunBacktest --sample` — **SmaCrossover only** (legacy quick demo, no strategy id)
  - `RunBacktest <strategyId> --sample` — synthetic bars (symbol-aware: JPY pairs use JPY-scale prices)
  - `RunBacktest <strategyId> <symbol> <year>` and `<symbol> <start-end>` via `HistoricalDataLoader.loadFromArgs`
  - `RunBacktest <strategyId> <path-to-.bars-or-.csv>` via `HistoricalDataLoader.loadFile`
  - Optional trailing `[capital]` (default `100_000`, same parsing rule as `RunPropBacktest`: last arg if numeric)
3. **AC3 — Legacy runners:** `RunPropBacktest` and `RunSqBacktest` remain as `@Deprecated` entry points:
  - Single-strategy invocations delegate to `RunBacktest.main(args)` unchanged
  - `RunPropBacktest --list` delegates to `RunBacktest --list` (or prints subset — must include all prop ids)
  - `RunPropBacktest --all` / `--all --sample` **stays in RunPropBacktest** (loops `PropStrategyCatalog`, calls shared `RunBacktest.runStrategy(...)` helper) — **not** ported to `RunBacktest`
4. **AC4 — Docs:** `AGENTS.md` and `docs/README.md` show one canonical `RunBacktest` command; legacy runners noted as deprecated aliases.
5. **AC5 — Tests:** `StrategyCatalogTest` asserts one resolvable id per family; `GoldenBacktestTest` uses `StrategyCatalog.create("LondonOpenRangeBreakout", ...)`; `mvn clean install` green; golden baseline unchanged.

## Tasks / Subtasks

- Task 1: Sub-catalogs + facade (AC: 1)
  - Add `SqImportedStrategyCatalog` — move 7 entries + `defaultSymbol()` from `RunSqBacktest.inferSymbol` map
  - Add `GeneratedStrategyCatalog` — `MyStrategy`, `Test123`, `TestFast` (all `String symbol` ctor)
  - Add `StrategyCatalog` facade delegating to prop/sq/generated + `register()` for examples
  - Static init: wire prop via `PropStrategyCatalog`, sq + generated in new catalogs
  - Duplicate IDs across families must throw at register time (fail fast)
- Task 2: Shared runner helper + `RunBacktest` CLI (AC: 2)
  - Extract package-visible `RunBacktest.runStrategy(Strategy, List<Bar>, double)` (or similar) for reuse by `RunPropBacktest --all`
  - `RunBacktest` static block: `StrategyCatalog.register("SmaCrossover", EXAMPLE, sym -> new SmaCrossoverStrategy("SMA 20/50", sym, 20, 50), "EUR_USD")`
  - Implement arg parser per AC2; remove hardcoded-only file-path mode
  - Centralize `--sample` bar generation (merge prop EUR-scale + sq GBP/JPY-scale logic into one helper keyed by symbol)
- Task 3: Deprecate legacy runners (AC: 3)
  - `RunSqBacktest.main` → `RunBacktest.main(args)` for all paths
  - `RunPropBacktest.main` → delegate single runs to `RunBacktest`; keep `--all` loop local using shared helper
  - Add `@Deprecated` + Javadoc pointing to `RunBacktest`
- Task 4: Docs (AC: 4)
  - `AGENTS.md` — canonical backtest section + deprecated runners note
  - `docs/README.md` — quick start backtest (French OK)
- Task 5: Tests (AC: 5)
  - `StrategyCatalogTest`: `LondonOpenRangeBreakout` (prop), `Strategy_2_14_147_Adapted` (sq), `MyStrategy` (generated), `SmaCrossover` registered in test or via package-visible test hook
  - Update `GoldenBacktestTest` to use `StrategyCatalog`
  - `mvn clean install`

## Dev Notes

### Story review (issues fixed in this revision)


| Issue                                                          | Resolution                                                                                       |
| -------------------------------------------------------------- | ------------------------------------------------------------------------------------------------ |
| AC1 vs Option B contradicted Task 1 (“merge Sma into catalog”) | Sma registered via `StrategyCatalog.register()` from `RunBacktest` static init — no module cycle |
| `--all` prop suite undefined                                   | Explicitly out of `RunBacktest`; stays in deprecated `RunPropBacktest`                           |
| Bare `--sample` vs `<id> --sample` ambiguous                   | Both specified: bare = SmaCrossover demo; with id = that strategy                                |
| `familyOf` optional                                            | Required `family()` for `--list` output                                                          |
| Missing `GeneratedStrategyCatalog`                             | Added as first-class sub-catalog                                                                 |
| Sq `defaultSymbol` scattered in RunSqBacktest                  | Move to `SqImportedStrategyCatalog.defaultSymbol()`                                              |
| Golden test “may use” StrategyCatalog                          | Changed to **must** in AC5                                                                       |
| No shared run helper for `--all` loop                          | Task 2 requires extract before deprecating RunProp                                               |


### Current state


| Runner            | Strategy resolution        | Data loading          |
| ----------------- | -------------------------- | --------------------- |
| `RunBacktest`     | SmaCrossover hardcoded     | `loadFile` only today |
| `RunPropBacktest` | `PropStrategyCatalog` (10) | `loadFromArgs`        |
| `RunSqBacktest`   | Inline map (7 sqimported)  | `loadFromArgs`        |


**Inventory (compiled, must register):**


| Family     | Count | IDs                                 | Factory signature         |
| ---------- | ----- | ----------------------------------- | ------------------------- |
| prop       | 10    | `LondonOpenRangeBreakout`, …        | `(String symbol)`         |
| sqimported | 7     | `Strategy_2_14_147_Adapted`, …      | no-arg ctor               |
| generated  | 3     | `MyStrategy`, `Test123`, `TestFast` | `(String symbol)`         |
| examples   | 1     | `SmaCrossover`                      | register from RunBacktest |


**Sq default symbols (from RunSqBacktest today — all GBP_JPY):**
`Strategy_2_14_147_Adapted`, `Strategy_2_15_195_Adapted`, `Strategy_2_31_175_Converted`, `Strategy_2_31_177_Converted`, `Strategy_2_32_120_Converted`, `Strategy_2_36_190_Converted`, `Strategy_2_38_112_Converted`

### Architecture (decided)

```
trading-strategies/
  StrategyCatalog.java              ← facade + register() hook
  prop/PropStrategyCatalog.java     ← unchanged, called by facade
  sqimported/SqImportedStrategyCatalog.java   ← NEW
  generated/GeneratedStrategyCatalog.java     ← NEW

trading-examples/
  RunBacktest.java                  ← CLI + SmaCrossover register() + sample helper
  RunPropBacktest.java              ← @Deprecated; --all stays here
  RunSqBacktest.java                ← @Deprecated; pure delegate
  SmaCrossoverStrategy.java         ← stays here
```

**Dependency rule:** `trading-strategies` must NOT depend on `trading-examples`. Example strategies register at runtime from `RunBacktest` only.

### Target commands

```bash
# Canonical
mvn exec:java -pl trading-examples \
  -Dexec.mainClass="com.martinfou.trading.examples.RunBacktest" \
  -Dexec.args="--list"

mvn exec:java -pl trading-examples \
  -Dexec.mainClass="com.martinfou.trading.examples.RunBacktest" \
  -Dexec.args="LondonOpenRangeBreakout EUR_USD 2012"

mvn exec:java -pl trading-examples \
  -Dexec.mainClass="com.martinfou.trading.examples.RunBacktest" \
  -Dexec.args="Strategy_2_14_147_Adapted GBP_JPY 2012"

# Legacy (still works)
mvn exec:java -pl trading-examples \
  -Dexec.mainClass="com.martinfou.trading.examples.RunPropBacktest" \
  -Dexec.args="LondonOpenRangeBreakout EUR_USD 2012"
```

### Previous story (12.2)

- Use `HistoricalDataLoader.loadFromArgs(StrategyCatalog.defaultSymbol(id), dataArgs)` when user omits symbol but not year — **only if** CLI supports 2-arg form `<id> <year>`; optional enhancement, not required if 3-arg form is mandatory.

### Scope boundaries

- No `getPendingOrders()` fixes (12.4)
- No `BatchStrategyRunner` CLI merge
- No `batch-results/` genetics exports
- Do not delete RunProp/RunSq classes

### Testing notes

- `StrategyCatalogTest`: for `SmaCrossover`, call `StrategyCatalog.register(...)` in `@BeforeAll` with a stub factory OR test only prop/sq/generated in module test and cover Sma in `trading-examples` smoke test — **prefer** package-visible `StrategyCatalog.clearForTest()` / register in test to keep one test class.
- Golden baseline: 8760 bars, 63 trades, +16.44%, $16,439.51 PnL — do not change constants.

### References

- [Source: _bmad-output/planning-artifacts/epics.md — Story 12.3]
- [Source: _bmad-output/implementation-artifacts/12-2-unified-historical-data-loading.md]
- [Source: trading-strategies/.../prop/PropStrategyCatalog.java]
- [Source: trading-examples/.../RunSqBacktest.java]
- [Source: trading-examples/.../RunPropBacktest.java]
- [Source: trading-examples/.../RunBacktest.java]
- [Source: trading-backtest/.../GoldenBacktestTest.java]

## Dev Agent Record

### Agent Model Used

Composer

### Debug Log References

- Partial implementation existed at story start (StrategyCatalog, sub-catalogs, RunBacktest refactor)
- Completed: StrategyCatalogTest, GoldenBacktestTest migration, AGENTS.md + docs/README.md updates
- `mvn clean install` green (2026-05-23)

### Completion Notes List

- Unified `StrategyCatalog` facade with prop/sq/generated bootstrap + EXAMPLE `register()` hook
- `RunBacktest` CLI supports --list, --help, --sample, strategy-id modes with HistoricalDataLoader
- `RunPropBacktest` / `RunSqBacktest` deprecated; `--all` prop suite retained in RunPropBacktest
- `StrategyCatalogTest` covers all four families; golden test uses StrategyCatalog.create()

### File List

- trading-strategies/src/main/java/com/martinfou/trading/strategies/StrategyCatalog.java (new)
- trading-strategies/src/main/java/com/martinfou/trading/strategies/sqimported/SqImportedStrategyCatalog.java (new)
- trading-strategies/src/main/java/com/martinfou/trading/strategies/generated/GeneratedStrategyCatalog.java (new)
- trading-strategies/src/test/java/com/martinfou/trading/strategies/StrategyCatalogTest.java (new)
- trading-examples/src/main/java/com/martinfou/trading/examples/RunBacktest.java (modified)
- trading-examples/src/main/java/com/martinfou/trading/examples/RunPropBacktest.java (modified)
- trading-examples/src/main/java/com/martinfou/trading/examples/RunSqBacktest.java (modified)
- trading-backtest/src/test/java/com/martinfou/trading/backtest/GoldenBacktestTest.java (modified)
- AGENTS.md (modified)
- docs/README.md (modified)

## Change Log

- 2026-05-23: Story 12.3 implemented — unified StrategyCatalog + RunBacktest CLI, legacy runners deprecated, tests and docs updated

