# AGENTS.md

Instructions for AI coding agents working in **Trading Bridge** — a Maven monorepo that converts StrategyQuant / JForex strategies to pure Java, with backtesting and broker connectors (OANDA, IBKR).

For detailed implementation rules, read `_bmad-output/project-context.md` first. Human-facing docs live in `docs/` (often French); code and agent docs are English.

## Scope

This file applies to the entire repository. Nested `AGENTS.md` files in subdirectories take precedence for files under that path.

## Quick reference

| Topic | Read first |
|-------|------------|
| Agent implementation rules | `_bmad-output/project-context.md` |
| Data models, Strategy API, XML shape | `docs/specs.md` |
| Sprint priorities | `docs/sprint-plan.md` |
| JForex → Java mapping | `docs/conversion-guide.md` |
| Architecture overview | `docs/README.md` |
| BMAD workflows | `.agents/skills/` |

## Tech stack

- **Java 21**, **Maven 4.x** multi-module, version `1.0.0-SNAPSHOT`
- **JUnit 5**, **Jackson 2.17**, **SLF4J 2.0**
- No Lombok, no Spring (unless explicitly requested)
- Adjacent Python dashboard in `dashboard/` — not part of the Maven reactor

## Module layout

Respect the dependency graph (acyclic; `trading-core` has no internal trading deps):

```
trading-core          ← domain models, Strategy interface, DataLoader
trading-backtest      → trading-core
trading-data          → trading-core
trading-parser        → trading-core
trading-broker        → (scaffold)
trading-strategies    → trading-core, trading-data
trading-examples      → trading-core, trading-backtest
trading-genetics      → (genetic optimization)
```

| Module | Purpose |
|--------|---------|
| `trading-core` | `Bar`, `Order`, `Strategy`, `DataLoader` |
| `trading-backtest` | `BacktestEngine`, `BacktestResult` |
| `trading-parser` | StrategyQuant XML → Java (Sprint 2) |
| `trading-data` | OANDA client, economic calendar |
| `trading-broker` | Broker connectors (Sprint 4) |
| `trading-strategies` | Live strategy runners |
| `trading-examples` | Sample strategies, backtest launcher |

## Build and test

```bash
# Full build (from repo root)
mvn clean install

# Tests only
mvn test

# Single module
mvn test -pl trading-data

# Sample backtest
mvn exec:java -pl trading-examples \
  -Dexec.mainClass="com.martinfou.trading.examples.RunBacktest" \
  -Dexec.args="--sample"

# Backtest with CSV data
mvn exec:java -pl trading-examples \
  -Dexec.mainClass="com.martinfou.trading.examples.RunBacktest" \
  -Dexec.args="/path/to/data.csv EURUSD"
```

Before marking work done: `mvn clean install` must pass for affected modules.

## Coding conventions

- **Packages:** `com.martinfou.trading.<module>`
- **Domain style:** Accessor methods (`symbol()`, `close()`), not JavaBeans getters
- **Time:** UTC everywhere internally (`Instant`); never `LocalDateTime.now()` for trading logic
- **Strategy pattern:** Queue orders privately; `getPendingOrders()` returns a copy and clears the queue
- **Backtest fills:** `MARKET` orders fill at `bar.open()`, not close
- **New dependencies:** Add to parent `dependencyManagement`, then reference without version in module POMs
- **Minimal diffs:** Only change code required by the task; match surrounding style

## Where to put new code

| Code type | Module |
|-----------|--------|
| Domain models, Strategy interface | `trading-core` |
| XML parsing, code generation | `trading-parser` |
| Broker / REST API clients | `trading-data` or `trading-broker` |
| Backtest engine changes | `trading-backtest` |
| Example / generated strategies | `trading-examples` |

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

**Sprint 2 — StrategyQuant XML parser** (`trading-parser`): `SqXmlParser`, `StrategyConfig`, indicators, entry/exit rules, optional Java codegen. Output must compile and backtest with `BacktestEngine`.

See `docs/sprint-plan.md` for full roadmap (Sprints 3–5: advanced backtest, brokers, production).
