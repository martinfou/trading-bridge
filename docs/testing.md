# Testing

## Golden backtest

Integration test `GoldenBacktestTest` validates end-to-end behaviour: historical data load, `BacktestEngine`, and the `LondonOpenRangeBreakout` prop strategy.

| Metric | Baseline | Tolerance |
|--------|----------|-----------|
| Bars (EUR_USD H1 2012) | 8760 | exact |
| Trades | 63 | exact |
| Total return % | 16.44 | ±1% |
| Total PnL | $16,439.51 | ±1% |
| Max drawdown % | 0.12 | ±0.01 |

- **Baseline commit:** `ec6dc72` (2026-05-23)
- **Initial capital:** $100,000
- **Data:** `data/historical/bars/EUR_USD_H1_2012.bars` or Dukascopy CSV equivalent

The test skips (does not fail) when local historical data is missing. CI machines without `data/historical/` will report the test as skipped.

### Re-capture baseline

```bash
mvn exec:java -pl trading-examples \
  -Dexec.mainClass="com.martinfou.trading.examples.RunPropBacktest" \
  -Dexec.args="LondonOpenRangeBreakout EUR_USD 2012"
```

Update constants in `trading-backtest/.../GoldenBacktestTest.java` and this table when behaviour intentionally changes.

### Run golden test only

```bash
mvn test -pl trading-backtest -Dtest=GoldenBacktestTest
```

## Build hygiene

If tests fail with `Unresolved compilation problem` or stale class errors, run a full clean build:

```bash
mvn clean install
```

See also `AGENTS.md` → Troubleshooting.

## Historical data formats

All backtest runners should load via `HistoricalDataLoader` (`trading-data`):

| Format | Location | Timestamps |
|--------|----------|------------|
| Dukascopy CSV | `data/historical/dukascopy/` | epoch millis in CSV |
| BarStore `.bars` | `data/historical/bars/` | epoch millis (legacy second-based files still readable) |

`scripts/download-data.sh` writes millis to `.bars`. Re-download or re-convert to migrate old second-based files.
