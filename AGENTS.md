# AGENTS.md

Instructions for AI coding agents working in **Trading Bridge** — a Maven monorepo that converts StrategyQuant / JForex strategies to pure Java, with backtesting and broker connectors (OANDA, IBKR).

For detailed implementation rules, read `_bmad-output/project-context.md` first. Human-facing docs live in `docs/` (often French); code and agent docs are English.

## Scope

This file applies to the entire repository. Nested `AGENTS.md` files in subdirectories take precedence for files under that path.

## Quick reference


| Topic                                   | Read first                                                 |
| --------------------------------------- | ---------------------------------------------------------- |
| Agent implementation rules              | `_bmad-output/project-context.md`                          |
| Data models, Strategy API, XML shape    | `docs/specs.md`                                            |
| Strategy module placement & order queue | `docs/strategy-home.md`                                    |
| Shared indicators (SMA, EMA, RSI, ATR)  | `com.martinfou.trading.core.indicators.Indicators`         |
| Sprint priorities                       | `docs/sprint-plan.md`                                      |
| JForex → Java mapping                   | `docs/conversion-guide.md`                                 |
| Architecture overview                   | `docs/architecture.md`                                     |
| Architecture overview (FR)              | `docs/README.md`                                           |
| Golden backtest & CI                    | `docs/testing.md`                                          |
| Epic / story tracking                   | `_bmad-output/implementation-artifacts/sprint-status.yaml` |
| BMAD workflows                          | `.agents/skills/`                                          |


## Tech stack

- **Java 21**, **Maven 4.x** multi-module, version `1.0.0-SNAPSHOT`
- **JUnit 5**, **Jackson 2.17**, **SLF4J 2.0**
- No Lombok, no Spring (unless explicitly requested)
- Adjacent Python dashboard in `dashboard/` — not part of the Maven reactor

## Module layout

Respect the dependency graph (acyclic; `trading-core` has no internal trading deps):

```
trading-core          ← domain, indicators, golden baseline
trading-backtest      → trading-core
trading-data          → trading-core
trading-parser        → trading-core
trading-broker        → trading-core
trading-strategies    → trading-core, trading-data
trading-genetics      → trading-core, trading-backtest
trading-examples      → trading-core, trading-backtest, trading-strategies, trading-data
trading-runtime       → trading-backtest, trading-strategies, trading-data, trading-broker
trading-tui           → (HTTP client; Jackson + JLine3 only)
```


| Module               | Purpose                                                                          |
| -------------------- | -------------------------------------------------------------------------------- |
| `trading-core`       | `Bar`, `Order`, `Strategy`, `DataLoader`, `Indicators`, `GoldenBacktestBaseline` |
| `trading-backtest`   | `BacktestEngine`, `RunContext`, `RunEvent`, reports                              |
| `trading-data`       | OANDA client, `HistoricalDataLoader`, economic calendar                          |
| `trading-broker`     | OANDA / IBKR broker connectors                                                   |
| `trading-strategies` | Prop, sqimported, generated strategies; `StrategyCatalog`                        |
| `trading-runtime`    | Control plane HTTP/WS, event store, promote gates, run lifecycle                 |
| `trading-tui`        | JLine3 terminal client for control plane                                         |
| `trading-parser`     | StrategyQuant XML → Java (Epic 2)                                                |
| `trading-examples`   | `RunBacktest` CLI, golden tests                                                  |
| `trading-genetics`   | Genetic optimization (offline)                                                   |


## Build and test

```bash
# Full build (from repo root)
mvn clean install

# Tests only
mvn test

# Single module
mvn test -pl trading-data

# List all strategies (prop, sqimported, generated, examples)
mvn exec:java -pl trading-examples \
  -Dexec.mainClass="com.martinfou.trading.examples.RunBacktest" \
  -Dexec.args="--list"

# Sample backtest (SmaCrossover demo)
mvn exec:java -pl trading-examples \
  -Dexec.mainClass="com.martinfou.trading.examples.RunBacktest" \
  -Dexec.args="--sample"

# Backtest by strategy id + historical data
mvn exec:java -pl trading-examples \
  -Dexec.mainClass="com.martinfou.trading.examples.RunBacktest" \
  -Dexec.args="LondonOpenRangeBreakout EUR_USD 2012"

# Backtest with file path
mvn exec:java -pl trading-examples \
  -Dexec.mainClass="com.martinfou.trading.examples.RunBacktest" \
  -Dexec.args="LondonOpenRangeBreakout /path/to/data.csv"

# Deprecated aliases (delegate to RunBacktest): RunPropBacktest, RunSqBacktest
# RunPropBacktest --all / --all --sample remains for prop suite runs only

# Control plane (port 8080 by default)
mvn exec:java -pl trading-runtime \
  -Dexec.mainClass="com.martinfou.trading.runtime.ControlPlaneMain"

# TUI client (requires running control plane)
mvn exec:java -pl trading-tui \
  -Dexec.mainClass="com.martinfou.trading.tui.TradingTuiMain"
```

Before marking work done: `mvn clean install` must pass for affected modules.

## Troubleshooting

If tests fail with `Unresolved compilation problem`, `cannot find symbol`, or behaviour that does not match source after a partial rebuild, stale classes under `target/` are often the cause. Run `mvn clean install` from the repo root before debugging further. The golden backtest (`GoldenBacktestTest`) skips when `data/historical/` is absent locally — see `docs/testing.md`.

## Coding conventions

- **Packages:** `com.martinfou.trading.<module>`
- **Domain style:** Accessor methods (`symbol()`, `close()`), not JavaBeans getters
- **Time:** UTC everywhere internally (`Instant`); never `LocalDateTime.now()` for trading logic
- **Strategy pattern:** Queue orders privately; `getPendingOrders()` returns a copy and clears the queue
- **Backtest fills:** `MARKET` orders fill at `bar.open()`, not close
- **New dependencies:** Add to parent `dependencyManagement`, then reference without version in module POMs
- **Minimal diffs:** Only change code required by the task; match surrounding style

## Where to put new code


| Code type                             | Module                             |
| ------------------------------------- | ---------------------------------- |
| Domain models, Strategy interface     | `trading-core`                     |
| XML parsing, code generation          | `trading-parser`                   |
| Broker / REST API clients             | `trading-data` or `trading-broker` |
| Backtest engine changes               | `trading-backtest`                 |
| Control plane / promote / event store | `trading-runtime`                  |
| Example / generated strategies        | `trading-examples`                 |
| Production strategies (prop, sq)      | `trading-strategies`               |


Do not put broker or API code in `trading-core`. Do not implement the parser in `trading-examples`.

## Security

- Never hardcode or commit API keys, tokens, or credentials
- Ignored paths: `.env`, `dashboard/oanda_creds.json`, `**/oanda_creds.json`
- OANDA credentials via environment or local config files only

## Git and artifacts

- Do not commit unless the user explicitly asks
- Do not commit `target/` build output
- BMAD planning/implementation artifacts go under `_bmad-output/`
- BMAD config overrides: `_bmad/custom/` (team) or `*.user.toml` (personal) — not `_bmad/config.toml`

## Active sprint

**Epic 12 — Platform consolidation** is complete (stories 12-1 … 12-11). **Epic 13 — Platform runtime** is complete (control plane, TUI, dashboard, promote gates).

Track implementation: `_bmad-output/implementation-artifacts/sprint-status.yaml`  
Vision roadmap: `docs/sprint-plan.md` · Architecture: `docs/architecture.md`
