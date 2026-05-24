---

## project_name: trading-bridge
user_name: Martinfou
date: '2026-05-17'
sections_completed:
  - technology_stack
  - language_rules
  - framework_rules
  - testing_rules
  - quality_rules
  - workflow_rules
  - anti_patterns
status: complete
rule_count: 42
optimized_for_llm: true

# Project Context for AI Agents

*This file contains critical rules and patterns that AI agents must follow when implementing code in this project. Focus on unobvious details that agents might otherwise miss.*

---

## Project Summary

**Trading Bridge** converts StrategyQuant / JForex strategies to pure Java, with backtesting and broker connectors (OANDA, IBKR). Maven monorepo, Java 21. **Active sprint:** Sprint 2 — StrategyQuant XML parser (`trading-parser`). Human docs live in `docs/` (`specs.md`, `sprint-plan.md`, `conversion-guide.md`).

---

## Technology Stack & Versions


| Component            | Version / choice                                                             |
| -------------------- | ---------------------------------------------------------------------------- |
| Java                 | 21 (`maven.compiler.source/target`)                                          |
| Build                | Maven 4.x multi-module, `1.0.0-SNAPSHOT`                                     |
| JUnit                | 5.11.0 (test scope, parent BOM)                                              |
| Jackson              | 2.17.2 (`jackson-databind`, `jackson-dataformat-yaml` in parent)             |
| SLF4J                | 2.0.16 (`slf4j-api`; `slf4j-simple` in `trading-strategies` only)            |
| Javaluator           | 3.0.3 (parent BOM, not yet used in source)                                   |
| HTTP (live data)     | `java.net.http.HttpClient` in `trading-data`                                 |
| Dashboard (adjacent) | Python `dashboard/oanda_server.py` + static HTML — not part of Maven reactor |


**Module dependency graph (respect this):**

```
trading-core          ← no internal trading deps
trading-backtest      → trading-core
trading-data          → trading-core
trading-parser        → trading-core   (scaffold: no Java yet)
trading-broker        → (scaffold: no Java yet)
trading-strategies    → trading-core, trading-data
trading-examples      → trading-core, trading-backtest
```

---

## Critical Implementation Rules

### Language-Specific Rules (Java)

- **Package root:** `com.martinfou.trading.<module>` — e.g. `com.martinfou.trading.core`, `com.martinfou.trading.backtest`.
- **Domain model style:** Plain classes with **accessor methods** (`symbol()`, `close()`), not JavaBeans (`getSymbol()`). Enums nested in domain types (`Order.Side`, `Order.Type`, `Order.Status`).
- **Records:** Use for small DTOs in integration code (e.g. `OandaPriceClient.Price`); keep core domain (`Bar`, `Order`, `Trade`, `Position`) as classes unless refactoring explicitly requested.
- **Time (canonical):** **UTC everywhere internally** — `Instant` on `Bar`, `Order`, `Trade` and at API/CSV/calendar boundaries; serialize as ISO-8601 with `Z`. Display only: `America/Toronto` via `TimeConventions`. See `docs/specs.md` §2.5. Never use `LocalDateTime.now()` for trading logic.
- **OANDA:** API timestamps are UTC; parse to `Instant`, not naive `LocalDateTime`.
- **Internal module deps:** Always `${project.version}` on `com.martinfou.trading:*` artifacts in child POMs.
- **No Lombok, no Spring** in current codebase — do not introduce without an explicit sprint/architecture decision.
- **Logging:** `org.slf4j` only; use `LoggerFactory.getLogger(Class)`. Bind `slf4j-simple` only in runnable modules that need console output.

### Framework / Domain Rules (Trading Bridge)

- `**Strategy` contract** (`trading-core`): Implement `name()`, `onBar(Bar)`, `onTick(bid, ask, volume)`, `getPendingOrders()`, `reset()`.
- **Order submission pattern:** Strategies queue orders in a private list; `getPendingOrders()` must **return a copy and clear** the internal queue (see `SmaCrossoverStrategy`). The engine consumes orders once per bar.
- **Backtest fill semantics:** `BacktestEngine` fills `MARKET` at `**bar.open()`** (not close). `LIMIT`/`STOP` use bar high/low rules — do not assume close-price fills.
- **Backtest limitations (do not assume implemented):** No commission/slippage, no open-position tracking across bars (simplified trade list), `stopLoss`/`takeProfit` on `Order` are not enforced by engine yet — Sprint 3 scope per `docs/sprint-plan.md`.
- **Data loading:** Use `DataLoader.loadCSV` (ISO `DateTime,Open,High,Low,Close,Volume`) or `loadStrategyQuantCSV` (`Date,Time,...` columns). Invalid rows are skipped silently.
- **JForex → Java mapping:** Follow `docs/conversion-guide.md` — `IStrategy` → `Strategy`, `IOrder` → `Order`, engine → `BacktestEngine` / future `Broker`.
- **Sprint 2 parser:** All XML parsing and code generation belongs in `**trading-parser`**, depending only on `trading-core`. Generated strategies implement `Strategy` in `trading-examples` or a dedicated package — not in `trading-core`.
- **Live / OANDA:** REST v3 via `OandaPriceClient` in `trading-data`; practice URL `api-fxpractice.oanda.com`. Credentials via env/config files — **never hardcode or commit keys**.

### Testing Rules

- **Framework:** JUnit 5 (`@Test`), tests under `src/test/java` mirroring main package.
- **Existing pattern:** `OandaTest` calls live-ish helpers (`EconomicCalendar.printWeek()`) — prefer unit tests with mocks for new code; mark integration tests clearly if they hit OANDA.
- **Run:** `mvn test` from root or `-pl trading-data` for a single module.
- **Before claiming done:** `mvn clean install` must pass for affected modules.

### Code Quality & Style Rules

- **Minimal diffs:** Only change code required by the task; no drive-by refactors or unrelated modules.
- **Match existing code:** Same naming, logging style, and dependency patterns as neighboring classes.
- **Comments:** Sparse — only for non-obvious trading logic or XML format quirks; no verbose JavaDoc on getters.
- **New dependencies:** Add to parent `dependencyManagement` first, then reference in module POM without version.
- **Executable examples:** Use `mvn exec:java -pl trading-examples -Dexec.mainClass=...` (see `docs/README.md`).

### Development Workflow Rules

- **Sprint source of truth:** `docs/sprint-plan.md` for priorities; align BMAD stories with Sprint 2–5 goals.
- **Specs:** `docs/specs.md` for data models, Strategy API, XML shape, broker interface sketches.
- **BMAD artifacts:** Planning/implementation outputs go under `_bmad-output/`; this file is the agent rules anchor.
- **Git:** Do not commit `dashboard/oanda_creds.json`, `.env`, or API tokens. User commits only when asked.
- **Docs language:** Human-facing project docs are often French; **code identifiers and this file are English**.

### Critical Don't-Miss Rules


| Don't                                           | Do instead                                                        |
| ----------------------------------------------- | ----------------------------------------------------------------- |
| Put broker/API code in `trading-core`           | `trading-data` or `trading-broker`                                |
| Implement parser in `trading-examples`          | `trading-parser` module                                           |
| Use `getPendingOrders()` without clearing queue | Copy list, then `pending.clear()`                                 |
| Assume SL/TP on `Order` work in backtest        | Engine ignores them until Sprint 3                                |
| Add Dukascopy/JForex JAR dependencies           | Pure Java + `Strategy` interface                                  |
| Break module DAG (e.g. core → backtest)         | Keep acyclic: core at bottom                                      |
| Edit `_bmad/config.toml` for prefs              | Use `_bmad/custom/config.toml` (team) or `*.user.toml` (personal) |
| Expand scope to Spring/SQLite dashboard         | Sprint 5 unless user requests                                     |


**Sprint 2 parser checklist (from specs):** `SqXmlParser`, `StrategyConfig` POJO, indicators (SMA first, then EMA/RSI/MACD/Bollinger/ATR), entry/exit rules, optional Java codegen — output must compile and backtest coherently with `BacktestEngine`.

---

## Key Paths


| Path                                   | Purpose                                     |
| -------------------------------------- | ------------------------------------------- |
| `trading-core/src/main/java/.../core/` | `Bar`, `Order`, `Strategy`, `DataLoader`    |
| `trading-backtest/.../backtest/`       | `BacktestEngine`, `BacktestResult`          |
| `trading-parser/`                      | Sprint 2 — XML → Java (empty today)         |
| `trading-data/.../data/`               | OANDA client, economic calendar             |
| `trading-strategies/`                  | Live strategy runners, signals              |
| `trading-examples/`                    | `RunBacktest`, `SmaCrossoverStrategy`       |
| `docs/`                                | Specs, sprint plan, JForex conversion guide |
| `_bmad-output/`                        | BMAD-generated artifacts                    |


---

## Usage Guidelines

**For AI Agents:**

- Read this file before implementing any code.
- Follow ALL rules exactly; when in doubt, prefer the more restrictive option.
- Cross-check `docs/specs.md` and `docs/conversion-guide.md` for trading semantics.
- Update this file if new patterns become project standard.

**For Humans:**

- Keep lean — agent-focused, not a duplicate of `docs/specs.md`.
- Update when stack or `Strategy`/engine contract changes.
- Review after each sprint completion.

**Last Updated:** 2026-05-17 (timezone conventions §2.5)