# Story 12.7: Strategy Contract & Home Policy (Deferred)

Status: backlog

## Story

As a strategy author,
I want a documented strategy home and a correct `getPendingOrders()` contract,
so that all strategies behave uniformly in backtest and live engines.

> **Note:** Reprioritized after brainstorming 2026-05-23. Pipeline stories 12.4–12.6 (RunContext, JSONL, paper) take precedence. Original epic definition preserved from `epics.md` Story 12.4.

## Acceptance Criteria

1. Audit sqimported strategies for `getPendingOrders()` copy-and-clear contract
2. Fix violations or wrap with adapter
3. Document strategy home policy: compiled → `trading-strategies/`, genetics → `batch-results/`, examples → `trading-examples/`
4. Remove or relocate orphan docs under wrong package paths

## References

- [Source: _bmad-output/planning-artifacts/epics.md — Story 12.4 original]