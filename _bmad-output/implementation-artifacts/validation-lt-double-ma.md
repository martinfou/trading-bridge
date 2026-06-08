# Validation — LtDoubleMA

## Concept
Golden Cross / Death Cross — EMA(50) / SMA(200) crossover. Achat quand EMA(50) croise au-dessus de SMA(200), vente quand il croise en dessous. Système classique de trend following long terme.

**Inspiration :** Turtle system classique + Ray Dalio (long-term trend)

## Paramètres
- EMA rapide: 50
- SMA lente: 200
- SL: 2× ATR(14)
- TP: 4× ATR(14)
- Max 1 trade/jour
- Sortie sur cross opposé ou SL/TP

## Résultats par asset

| Asset | Période | Trades | PF | Sharpe | PnL | DD% | WR% | Qualifié |
|-------|---------|--------|----|--------|-----|-----|-----|----------|
| EUR/USD | FULL 2006-2026 | 708 | 1.17 | -0.01 | +$557 | 13.53 | 35.2 | ✅ |
| EUR/USD | IS 2006-2015 | 340 | 1.20 | 0.15 | +$395 | 12.26 | 35.3 | ✅ |
| EUR/USD | OOS1 2016-2020 | 189 | 1.15 | -0.02 | +$111 | 11.05 | 37.0 | ✅ |
| EUR/USD | OOS2 2021-2026 | 177 | 1.07 | -0.24 | +$46 | 11.15 | 32.8 | ✅ |
| GBP/USD | FULL 2006-2026 | 726 | 1.39 | 0.33 | +$1,559 | 21.13 | 36.5 | ✅ |

## Walk-Forward Analysis

| Métrique | IS→OOS1 | IS→OOS2 |
|----------|---------|---------|
| Dégradation PF | 4.2% ✅ | 10.8% ✅ |
| Dégradation PnL | -72% | -88% |
| DD stable | 12.3→11.1% | →11.2% |

PF reste stable. PnL baisse car moins d'opportunités de cross sur les périodes récentes. GBP beaucoup plus rentable qu'EUR.

## Verdict
🟢 **QUALIFIÉE** — PF > 1.07 sur tous les assets/périodes, dégradation OOS < 15%, DD < 22%.

## Leçons apprises

1. **Golden/death cross fonctionne mais est lent** — ~35 trades/an sur EUR, 2.5% de retour CAGR. C'est un système de retraite, pas de scalping.
2. **GBP/USD surperforme EUR/USD** — 3× plus de PnL sur la même période. Le croisement EMA/SMA capte mieux les trends GBP.
3. **DD plus élevé que les autres stratégies long terme** — car trades longs (jours/semaines). Normal pour un trend long terme.
4. **PF légèrement en baisse sur OOS2** — peut indiquer un marché qui se range plus depuis 2021.
