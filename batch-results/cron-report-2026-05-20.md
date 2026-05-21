# Backtest Cron Report — Wed 20 May 2026 21:00 EDT

## 1. Git Check (last 6h)

No new commits pushed since morning run. Last commits are from earlier today:

| Commit | Description |
|--------|-------------|
| 207ffa7 | RunBacktest proxy refactor (DataProxy, --proxy local/oanda) |
| 5dc186e | JPY quote conversion fix |
| d72a484 | JForex backtest runner + EUR/USD H1 2026 results |

## 2. Pipeline Health

Project compiled successfully (mvn compile ✅). Quick validation backtest with default SMA:

| Metric | Value |
|--------|-------|
| Pair | EUR/USD |
| Period | Mar '25 — May '26 |
| Trades | 128 |
| Win Rate | 33.6% (❌ < 45%) |
| Sharpe | 4.10 (✅ > 1.0) |
| Profit Factor | 1.35 |
| Max DD | 1.58% |
| **Passes criteria** | **No** — WinRate too low |

## 3. Weekly Strategy Status

- `WeeklyStrategy.java` generated at 07:00 EDT (base framework only)
- No concrete weekly strategy config JSON found in `deploy/weekly-plans/`
- No generated concrete strategy classes (no config processed by `WeeklyStrategyGenerator`)
- Deploy directory is empty — no Saturday analysis JSON available

## 4. Batch Results Summary

| Batch | Strategies | Pass Criteria | Notes |
|-------|-----------|---------------|-------|
| 2025 batch | 200 | 0 | All negative Sharpe |
| 2026 batch | 500 | 0 | All negative Sharpe |
| Test batch | 50 | 0 | All negative Sharpe |

## 5. Promotion

**No candidates pass criteria (Sharpe > 1.0 AND WinRate > 45%).** Nothing to promote.

## 6. Conclusion

Pipeline operational ✅. No new strategies to backtest or promote since the 09:00 EDT run.
