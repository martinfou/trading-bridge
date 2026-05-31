# Deferred Work

Items deferred during code reviews — not blocking current stories.

## Deferred from: code review of 2-4-core-indicators-sma-ema-rsi (2026-05-30)

- **RSI ignore `#ComputedFrom#`** — `SqCoreIndicators.rsi` delegates to close-only `Indicators.rsi` (AC5). Applied-price RSI if needed in 2-5+.
- **Shift négatif → OOB possible** — `SqIndicatorParams.endIndex` not clamped; SQ spinners emit shift ≥ 0; add param validation in 2-6+.
- **`period` ≤ 0 non gardé** — Theoretical divide-by-zero; SQ periods are positive in real fixtures.
- **EMA avec shift non testé** — SMA shift coverage sufficient for 2-4; add EMA shift test in 2-5 if needed.

## Deferred from: code review of 2-5-extended-indicators-macd-bollinger-atr (2026-05-30)

- **MACD signal / histogram not exposed** — `#SignalPeriod#` parsed but only MACD line returned; signal/histogram in 2-6+ if SQ rules need them.
- **No registry alias `BB`** — Catalogue text uses `BB(period,mult)`; register alias if fixture confirms SQ item key.
- **Bollinger upper/lower not exposed** — `BollingerBands` returns middle per AC3; upper/lower via `#Line#` or separate keys in 2-6+.
- **Partial registry dispatch tests** — Only ATR tested via registry; MACD/BB covered in `SqExtendedIndicatorsTest`.
- **Negative shift / period ≤ 0** — Carried from 2-4; param validation in 2-6+.
