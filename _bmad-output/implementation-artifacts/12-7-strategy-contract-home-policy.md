# Story 12.7: Strategy Contract & Home Policy

Status: done

## Story

As a strategy author,
I want a documented strategy home and a correct `getPendingOrders()` contract,
so that all strategies behave uniformly in backtest and live engines.

## Acceptance Criteria

- [x] Audit sqimported strategies for `getPendingOrders()` copy-and-clear contract
- [x] Fix violations or wrap with adapter
- [x] Document strategy home policy: compiled → `trading-strategies/`, genetics → `batch-results/`, examples → `trading-examples/`
- [x] Remove or relocate orphan docs under wrong package paths

## Implementation

### Contract fix

Added `StrategyOrderQueues.drainPending(List<Order>)` in `trading-strategies`.

**Fixed (7 sqimported violations):**

| Class | Notes |
|-------|-------|
| `Strategy_2_14_147_Adapted` | drain after PENDING filter |
| `Strategy_2_15_195_Adapted` | preserves activeOrder reset before drain |
| `Strategy_2_31_175_Converted` | drain |
| `Strategy_2_31_177_Converted` | drain |
| `Strategy_2_32_120_Converted` | drain |
| `Strategy_2_36_190_Converted` | drain |
| `Strategy_2_38_112_Converted` | drain |

**Already compliant:** `AbstractPropStrategy`, `generated/*`, most creative strategies (inline copy-and-clear).

### Documentation

- `docs/strategy-home.md` — module placement table + order queue contract
- `docs/specs.md` — cross-link on `getPendingOrders()`
- `AGENTS.md` — quick reference row

### Orphan docs relocated

From `trading-strategies/src/main/java/com/trading/strategies/sqimported/` → `docs/sqimported/`:

- `CATALOGUE.md`, `CATALOGUE_V2.md`, `README.md`

### Tests

- `StrategyOrderQueuesTest` — copy-and-clear + filled-order filter

## References

- [Source: _bmad-output/planning-artifacts/sprint-12-consolidation-plan.md]
- [Source: docs/strategy-home.md]
