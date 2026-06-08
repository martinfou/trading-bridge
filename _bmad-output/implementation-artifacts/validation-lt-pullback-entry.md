# Validation — LtPullbackEntry

## Concept
Pullback entry in trend direction. Identifier la tendance avec EMA(200)/SMA(100), puis entrer sur pullback vers EMA(50) avec RSI(14) comme filtre de momentum.

- Uptrend (close > SMA(100)) : BUY quand price près d'EMA(50) ET RSI(14) < 40
- Downtrend (close < SMA(100)) : SELL quand price près d'EMA(50) ET RSI(14) > 60
- SL=2×ATR(14), TP=4×ATR(14)

**Inspiration :** Ed Seykota (trend is your friend) + Larry Hite (money management)

## Résultats partiels

| Asset | Période | Trades | PF | PnL | DD% | WR% |
|-------|---------|--------|----|-----|-----|-----|
| EUR/USD | FULL 2006-2026 | 2,907 | **0.78** | **-$2,235** | **217.78** | 37.3 |
| EUR/USD | IS 2006-2015 | 1,405 | **0.75** | **-$1,498** | **147.97** | 36.3 |

## Verdict
🔴 **ÉCHEC** — PF < 1.0, pertes massives, drawdown catastrophique > 200%. Stratégie invalide.

## Leçons apprises (Post-Mortem)

1. **Filtres trop restrictifs = trades forcés** — 2907 trades en 20 ans (145/an), mais le filtre RSI < 40 / > 60 attrape des entrées qui ne sont pas de vrais pullbacks.
2. **Proximité EMA(50) à 0.1% trop serrée** — le prix ne revient pas assez souvent exactement sur l'EMA en H1. Les entrées sont forcées par des touches approximatives.
3. **SL=2× ATR trop serré pour un pullback** — sur H1, un pullback peut facilement retracer 2× ATR avant de repartir dans le bon sens.
4. **Le drawdown de 217% est fatal** — avec le capital fixe et pas de risk management, la stratégie explode.
5. **Concept valide sur papier, invalide en exécution H1** — pullback entry fonctionne mieux sur H4/Daily. Sur H1, trop de bruit.
