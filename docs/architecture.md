# Architecture (Trading Bridge)

English reference for agents and operators. Human-facing overview: `docs/README.md` (French). Implementation rules: `_bmad-output/project-context.md`, `AGENTS.md`.

## System overview

```mermaid
flowchart TB
    subgraph clients [Control surfaces]
        TUI[trading-tui JLine3]
        WEB[dashboard Laravel]
    end

    subgraph runtime [trading-runtime]
        CP[ControlPlane HTTP + WS]
        RM[RunManager / lifecycle]
        ES[EventStore SQLite]
        PG[Promote gates]
    end

    subgraph engine [Backtest & execution]
        RC[RunContext / RunEvent JSONL]
        BE[BacktestEngine]
        BR[trading-broker OANDA / IBKR]
    end

    subgraph strategies [trading-strategies]
        CAT[StrategyCatalog families]
        PROP[prop / sqimported / generated]
    end

    subgraph core [trading-core]
        DOM[Bar Order Strategy Trade]
        IND[Indicators GoldenBacktestBaseline]
    end

    DATA[trading-data OANDA calendar loader]
    CLI[trading-examples RunBacktest]

    TUI --> CP
    WEB --> CP
    CP --> RM
    RM --> RC
    RC --> BE
    RC --> BR
    BE --> CAT
    BR --> CAT
    CAT --> PROP
    PROP --> DOM
    BE --> DOM
    DATA --> DOM
    CLI --> RC
```



## Maven modules (dependency graph)

Acyclic — `trading-core` has no internal trading dependencies.

```
trading-core
├── trading-backtest
├── trading-data
├── trading-parser          (scaffold)
├── trading-broker          → trading-core
├── trading-strategies      → trading-core, trading-data
├── trading-genetics        → trading-core, trading-backtest
├── trading-examples        → trading-core, trading-backtest, trading-strategies, trading-data
├── trading-runtime         → trading-backtest, trading-strategies, trading-data, trading-broker
└── trading-tui             (HTTP client only — not on engine classpath)
```


| Module               | Role                                                                                               |
| -------------------- | -------------------------------------------------------------------------------------------------- |
| `trading-core`       | Domain models, `Strategy`, `DataLoader`, `TimeConventions`, `Indicators`, `GoldenBacktestBaseline` |
| `trading-backtest`   | `BacktestEngine`, `RunContext`, `RunEvent`, reports, paper stub                                    |
| `trading-data`       | `HistoricalDataLoader`, OANDA client, economic calendar                                            |
| `trading-strategies` | Prop / SQ / generated strategies, `StrategyCatalog`, `LiveStrategyRunner`                          |
| `trading-broker`     | `Broker` implementations (OANDA, IBKR paper/live)                                                  |
| `trading-runtime`    | Control plane, event store, promote gates, run lifecycle                                           |
| `trading-tui`        | Terminal client for control plane                                                                  |
| `trading-examples`   | `RunBacktest` CLI, golden tests                                                                    |
| `trading-parser`     | StrategyQuant XML → Java (Epic 2, scaffold)                                                        |
| `trading-genetics`   | Genetic strategy search (batch, not runtime catalog)                                               |


**Outside reactor:** `dashboard/` (Laravel control room), `batch-results/` (GA output, not compiled).

## Runtime data layout


| Path               | Purpose                                              |
| ------------------ | ---------------------------------------------------- |
| `data/historical/` | Full-year CSV / `.bars` (local, not in git)          |
| `data/ci/`         | Committed mini-dataset for golden CI                 |
| `data/runtime/`    | SQLite event store, deployment store, threshold JSON |


## Entry points

```bash
# Backtest CLI (all catalog strategies)
mvn exec:java -pl trading-examples \
  -Dexec.mainClass=com.martinfou.trading.examples.RunBacktest \
  -Dexec.args="--list"

# Control plane (default port 8080)
mvn exec:java -pl trading-runtime \
  -Dexec.mainClass=com.martinfou.trading.runtime.ControlPlaneMain

# TUI client (control plane must be running)
mvn exec:java -pl trading-tui \
  -Dexec.mainClass=com.martinfou.trading.tui.TradingTuiMain
```

## Strategy placement

See `docs/strategy-home.md` — prop/sqimported/generated live in `trading-strategies`; examples in catalog via `trading-examples`.

## Sprint / epic tracking

**Implementation truth:** `_bmad-output/implementation-artifacts/sprint-status.yaml`  
**Vision roadmap:** `docs/sprint-plan.md` (long-term modules; epic numbers may differ from BMAD epics 12/13).

## Related docs


| Doc                        | Content                                |
| -------------------------- | -------------------------------------- |
| `docs/specs.md`            | Strategy API, time (UTC), XML shape    |
| `docs/testing.md`          | Golden backtest, promote gates         |
| `docs/strategy-home.md`    | Module placement, order queue contract |
| `docs/conversion-guide.md` | JForex → Java                          |


