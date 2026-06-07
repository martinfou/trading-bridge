# Strategy home policy (Epic 12)

Where compiled strategies live and how the order queue contract works.

## Module placement

| Family | Directory | Maven module | Catalog |
|--------|-----------|--------------|---------|
| Prop / production | `trading-strategies/.../prop/` | `trading-strategies` | `PropStrategyCatalog` |
| StrategyQuant imports | `trading-strategies/.../sqimported/` | `trading-strategies` | `SqImportedStrategyCatalog` |
| Parser / genetics output | `trading-strategies/.../generated/` | `trading-strategies` | `GeneratedStrategyCatalog` |
| Creative / experimental | `trading-strategies/.../creative/` | `trading-strategies` | (not in unified catalog) |
| Examples & demos | `trading-examples/.../` | `trading-examples` | `StrategyCatalog.Family.EXAMPLE` |
| Genetics templates | `trading-genetics/` | `trading-genetics` | not in runtime catalog |
| Batch GA results / Backtests | [docs/batch-backtest-results.md](batch-backtest-results.md) | **not compiled** | reference only |

**Rule:** Only code under `trading-strategies` and registered `trading-examples` entries are reachable via `StrategyCatalog` / `RunBacktest`.

## `getPendingOrders()` contract

Per `Strategy` interface and `BacktestEngine`:

1. Strategy enqueues orders privately during `onBar` / `onTick`.
2. Engine calls `getPendingOrders()` **once per bar**.
3. Implementation must **return a copy** and **clear** the internal queue.
4. Use `StrategyOrderQueues.drainPending(List<Order>)` for list-backed queues.

Violations (returning live list) cause duplicate fills across bars.

## Adding a new strategy

1. Pick the home row above.
2. Register in the family catalog (or `StrategyCatalog.register` for examples).
3. Implement copy-and-clear in `getPendingOrders()`.
4. Use `com.martinfou.trading.core.indicators.Indicators` for SMA/EMA/RSI/ATR — do not duplicate in strategy classes.
5. Add to `RunBacktest --list` smoke if production-facing.

See also: `docs/specs.md` § Strategy interface, `AGENTS.md` module table.
