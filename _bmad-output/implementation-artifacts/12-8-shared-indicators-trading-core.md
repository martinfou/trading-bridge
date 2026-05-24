# Story 12.8: Shared Indicators in trading-core (Deferred)

Status: backlog

## Story

As a strategy author,
I want shared indicator utilities in `trading-core`,
so that prop, sqimported, and generated strategies do not duplicate SMA/EMA/RSI/ATR logic.

> **Note:** Deferred until pipeline 12.4–12.6 complete. Original epic definition from `epics.md` Story 12.5.

## Acceptance Criteria

1. Introduce `com.martinfou.trading.core.indicators`
2. Migrate prop strategies from `PropIndicators` without golden PnL regression
3. New strategies use core indicators by default

## References

- [Source: _bmad-output/planning-artifacts/epics.md — Story 12.5 original]