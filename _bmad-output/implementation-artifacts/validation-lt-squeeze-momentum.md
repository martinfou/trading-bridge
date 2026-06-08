# Validation — LtSqueezeMomentum

## Concept
Bollinger Squeeze (bandwidth < 0.05) avec confirmation RSI(14) pour la direction. Pendant les périodes de volatilité extrêmement basse, la direction du prochain breakout est déterminée par le momentum relatif.

- Quand BB(20,2) bandwidth < 0.05 (squeeze) :
  - RSI(14) > 55 → BUY (momentum haussier dans le squeeze)
  - RSI(14) < 45 → SELL (momentum baissier dans le squeeze)
- Sortie quand bandwidth s'élargit > 0.10 ou SL/TP
- SL=2×ATR(14), TP=4×ATR(14)

**Inspiration :** Quantified Strategies (squeeze play) + J.W. Henry (trend following)

## Résultats par asset

| Asset | Période | Trades | PF | Sharpe | PnL | DD% | WR% | Qualifié |
|-------|---------|--------|----|--------|-----|-----|-----|----------|
| EUR/USD | FULL 2006-2026 | 3,853 | 1.06 | 0.15 | +$1,038 | 34.95 | 33.6 | ✅ |
| EUR/USD | IS 2006-2015 | 1,906 | 1.05 | 0.22 | +$603 | 30.91 | 32.0 | ✅ |
| EUR/USD | OOS1 2016-2020 | 966 | **1.00** | -0.10 | +$17 | 19.70 | 34.3 | ⚠️ |
| EUR/USD | OOS2 2021-2026 | 950 | 1.13 | 0.51 | +$412 | 15.19 | 36.2 | ✅ |
| GBP/USD | FULL 2006-2026 | 4,131 | 1.10 | 0.38 | +$2,186 | 25.23 | 34.3 | ✅ |

## Walk-Forward Analysis

| Métrique | IS→OOS1 | IS→OOS2 |
|----------|---------|---------|
| Dégradation PF | -4.8% | +7.6% |
| PnL | -97% | -32% |
| DD | 30.9→19.7% | →15.2% |

OOS1 (2016-2020) est flat — période de tendances faibles. OOS2 (2021-2026) a bien repris. La stratégie survit au walk-forward mais avec des performances cycliques.

## Verdict
🟢 **QUALIFIÉE** — PF > 1.0 sur tous sauf OOS1 (1.00 = breakeven). GBP surperforme. Drawdown élevé (~35% max) mais acceptable.

## Leçons apprises

1. **Bollinger squeeze est un signal rare** — ~190 trades/an, ce qui est peu pour une stratégie H1. Le squeeze ne se produit pas souvent sur le forex.
2. **OOS1 flat = le squeeze ne fonctionne pas en range** — 2016-2020 était une période de faible volatilité directionnelle. Les squeeze breakouts sont moins fiables en range.
3. **GBP/USD surperforme** — 2× le PnL d'EUR/USD. Les squeeze sur GBP sont plus explosifs.
4. **WR bas (33-34%) mais PF > 1.0** — les trades gagnants sont plus gros que les perdants, ce qui compense le faible win rate. Trading de momentum typique.
5. **DD élevé dû aux trades longs** — le squeeze peut prendre des dizaines de bars avant d'expand. Normal pour cette approche.
