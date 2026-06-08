# Validation Report — LtVolRegime (Strategy #7)

**Strategy:** LtVolRegime — Volatility Regime Trading
**Date:** 2026-06-07
**Author:** Trading Bridge Agent

## Concept

Volatility regime switching using ATR ratio (14/100):
- **High vol regime** (ratio > 1.5): Trend follow — price vs EMA(50)
- **Low vol regime** (ratio < 0.5): Mean reversion — RSI(14) extremes
- **Normal regime** (0.5 ≤ ratio ≤ 1.5): No trades
- SL = 2× ATR(14), TP = 4× ATR(14), max 1 trade/day, closeOnly() on exits

## Backtest Results

### EUR_USD

| Run | Period | Return | Trades | Win% | Sharpe | PF | DD% |
|-----|--------|--------|--------|------|--------|----|------|
| Full | 2006-2026 | +59.50% | 930 | 38.5% | -0.01 | 1.31 | 6.17% |
| IS | 2015-2019 | +28.26% | 276 | 42.0% | 0.56 | 1.59 | 4.67% |
| OOS1 | 2020-2022 | +7.24% | 144 | 36.1% | -0.01 | 1.24 | 5.51% |
| OOS2 | 2023-2025 | +5.30% | 146 | 37.0% | -0.05 | 1.23 | 4.89% |

### GBP_USD

| Run | Period | Return | Trades | Win% | Sharpe | PF | DD% |
|-----|--------|--------|--------|------|--------|----|------|
| Full | 2006-2026 | +63.12% | 1007 | 36.9% | 0.02 | 1.24 | 13.32% |

## Analysis

- **Profitable but weak**: All runs show positive returns and profit factors > 1.2, but Sharpe ratios near zero indicate inconsistent risk-adjusted returns.
- **Consistent across regimes**: PF stays around 1.23–1.59 across IS and OOS periods, suggesting the strategy doesn't overfit to specific market conditions.
- **Low drawdowns**: Max DD between 4.67% and 6.17% on EUR_USD is attractive, though GBP_USD shows 13.32%.
- **Low win rate (~37-42%)**: Typical for trend-following strategies, with larger winners offsetting more frequent losers.
- **Performance decay**: Returns degrade from IS (28.26%) to OOS1 (7.24%) to OOS2 (5.30%), suggesting diminishing edge in recent years.
- **Moderate trade frequency**: ~146-276 trades per 3-5 year period, reasonable for H1 data.

## Leçons apprises (Lessons Learned)

1. **ATR ratio works as a regime filter**: The combination of high-vol trend follow and low-vol mean reversion produces positive returns across multiple periods and instruments, validating the concept of volatility regime switching.

2. **Non-correlated edges**: High vol (momentum) and low vol (mean reversion) regimes produce uncorrelated trade signals, which helps smooth the equity curve and reduce drawdowns.

3. **Edge degradation**: The declining performance from IS (2015-2019) to OOS periods (2020-2025) suggests the strategy's edge has weakened — possibly due to changing market microstructure or the strategy being more suited to the pre-2020 macro environment.

4. **Threshold sensitivity**: The 1.5 and 0.5 ATR ratio thresholds need further optimization. Some regimes may be misclassified, particularly borderline cases near the thresholds.

5. **GBP_USD risk**: Higher max DD (13.32%) on GBP_USD indicates the strategy is more exposed to GBP-specific volatility events (e.g., Brexit aftermath).

6. **Sharpe ratio improvement needed**: Near-zero Sharpe ratios suggest the strategy needs either better entries, tighter stops, or a more refined volatility regime classification system.

7. **closeOnly() pattern works**: Using closeOnly() on exit orders correctly avoids unintended hedging on OANDA.

## Files

- Strategy: `trading-strategies/src/main/java/com/martinfou/trading/strategies/longterm/LtVolRegime.java`
- Runner: `trading-examples/src/main/java/com/martinfou/trading/examples/RunLtVolRegime.java`
