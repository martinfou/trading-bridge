# Story 12.8: Shared Indicators in trading-core

Status: done

## Story

As a strategy author,
I want shared indicator utilities in `trading-core`,
so that prop, sqimported, and generated strategies do not duplicate SMA/EMA/RSI/ATR logic.

## Acceptance Criteria

- [x] Introduce `com.martinfou.trading.core.indicators`
- [x] Migrate prop strategies from `PropIndicators` without golden PnL regression
- [x] New strategies use core indicators by default

## Implementation

### Core package

`com.martinfou.trading.core.indicators.Indicators` — SMA, EMA, RSI, RSI(2), ATR (true range), Bollinger width, engulfing patterns, pip size, risk/reward TP helper, `TradeSide` enum.

### Migration

- **Removed** `PropIndicators.java` — all 11 prop strategy classes + `AbstractPropStrategy` + `PropSessions` now call `Indicators` directly.
- **`MarketAnalyzer`** — delegates SMA/RSI to core; keeps simplified range-ATR and `findKeyLevels`/`trend` for news-trading heuristics.
- Removed unused `MarketAnalyzer` imports from sqimported adapted strategies.

### Tests

- `IndicatorsTest` (trading-core)
- Golden backtest unchanged (`GoldenBacktestTest` CI subset)

## References

- [Source: docs/strategy-home.md]
- [Source: AGENTS.md quick reference]
