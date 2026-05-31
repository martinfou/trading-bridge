# CI golden backtest subset

Committed mini-dataset for `GoldenBacktestTest` (story 13.8). Always available in CI without `data/historical/`.

| Field | Value |
|-------|-------|
| File | `EUR_USD_H1_subset.csv` |
| Symbol | EUR_USD H1 |
| Period | January 2012 (744 bars) |
| Source | First 744 rows of `data/historical/dukascopy/eurusd-h1-bid-2012-01-01-2012-12-31.csv` |
| Strategy | `LondonOpenRangeBreakout` |
| Regenerate | `./scripts/generate-ci-golden-subset.sh` (requires local full 2012 CSV) |

See `docs/testing.md` for expected metrics.
