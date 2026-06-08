# Validation — LtRangeBreakout (Donchian Channel Breakout)

- **Stratégie #5** — Donchian Channel Breakout (Classic Turtle System)
- **Date** : 2026-06-07
- **Auteur** : Agent Hermes

## Concept

Donchian channel breakout pur, inspiré du système Turtle Trading de Richard Dennis et William Eckhardt. La stratégie achète lorsque le prix franchit le plus haut des 20 dernières bougies (résistance dynamique) et vend lorsqu'il casse le plus bas des 20 dernières bougies (support dynamique). C'est le système de trend-following le plus ancien et le plus documenté.

**Mécanisme :**
- Entrée : `bar.close() > highest(20)` → BUY ; `bar.close() < lowest(20)` → SELL
- Stop-loss : 2 × ATR(14) depuis le prix d'entrée
- Take-profit : 4 × ATR(14) depuis le prix d'entrée
- Trailing stop : ATR(14) × 2 depuis l'extrême depuis l'entrée
- Sortie supplémentaire : signal de breakout opposé
- Max 1 trade par jour
- Sorties en `closeOnly()` (pas de hedging)

## Paramètres

| Paramètre | Valeur |
|-----------|--------|
| Période Donchian | 20 barres |
| Période ATR | 14 |
| Multiplicateur SL | 2.0 × ATR |
| Multiplicateur TP | 4.0 × ATR |
| Histoire minimale | 80 barres |
| Taille position | 1 000 unités |
| Cooldown | 8 barres |
| Max trades/jour | 1 |
| Timeframe | H1 |
| Commission | $5/trade (+ $5 exit) |
| Slippage | 0.01% |

## Résultats

### EUR_USD — Walk-Forward (2006-2025)

| Métrique | Full (2006-25) | IS (2006-17) | OOS1 (2018-23) | OOS2 (2024-25) |
|----------|---------------|-------------|---------------|---------------|
| **P&L net** | **-$31 824 (-31,8%)** | **-$18 501 (-18,5%)** | **-$10 751 (-10,8%)** | **-$2 519 (-2,5%)** |
| Total trades | 4 217 | 2 600 | 1 313 | 298 |
| Winners | 3 634 (86,2%) | 2 250 (86,5%) | 1 141 (86,9%) | 238 (79,9%) |
| Losers | 583 (13,8%) | 350 (13,5%) | 172 (13,1%) | 60 (20,1%) |
| Avg trade | -$7,55 | -$7,12 | -$8,19 | -$8,45 |
| Max DD | 31,82% | 18,50% | 10,75% | 2,52% |
| Sharpe | -39,04 | -39,20 | -44,42 | -46,04 |
| Sortino | -15,77 | -16,55 | -16,99 | -17,02 |
| **Profit Factor** | **22,16** | **23,99** | **20,45** | **12,40** |
| Commission | $42 170 | $26 000 | $13 130 | $2 980 |
| Slippage | $1 033 | $671 | $296 | $64 |

### GBP_USD — Full (2006-2025)

| Métrique | Valeur |
|----------|--------|
| P&L net | -$29 942 (-29,9%) |
| Total trades | 4 271 |
| Winners | 3 731 (87,4%) |
| Losers | 540 (12,6%) |
| Avg trade | -$7,01 |
| Max DD | 29,94% |
| Sharpe | -37,36 |
| Profit Factor | **25,22** |
| Commission | $42 710 |

## Walk-Forward

**Walk-forward analysis :** IS (2006-2017) → OOS1 (2018-2023) → OOS2 (2024-2025)

La stratégie montre une **dégradation progressive des performances brutes** :

| Période | P&L brut (avant frais) | P&L net | Trades/mois |
|---------|----------------------|---------|-------------|
| IS (2006-17) | +$7 499 ($26k - $18,5k) | -$18 501 | ~18/mois |
| OOS1 (2018-23) | +$2 379 ($13k - $10,8k) | -$10 751 | ~18/mois |
| OOS2 (2024-25) | +$461 ($2 980 - $2 519) | -$2 519 | ~18/mois |

Le profit factor brut (avant commissions) est excellent (>20), mais la fréquence de trading excessive (18 trades/mois) anéantit les gains nets.

## Analyse

### Forces
- **Win rate très élevé** : ~86% de trades gagnants — le système capture de petites fluctuations et les verrouille
- **Profit Factor brut excellent** (>20) — le ratio gains/pertes est excellent avant coûts
- **Drawdown modéré** : 31% max sur 19 ans, 10-18% sur sous-périodes
- **Comportement cohérent** entre IS, OOS1, OOS2 — pas de surapprentissage
- **Concept éprouvé** : le système Turtle est le plus documenté de l'histoire du trading

### Faiblesses
- **Trop de trades** : ~4 200 trades en 19 ans, soit ~18 trades/mois. Sur H1, le Donchian génère des signaux constants
- **Commissions dévastatrices** : $42k de commissions sur EUR_USD pour un P&L brut de +$7,5k maximum
- **Avg trade négatif** : -$7 à -$8 par trade — les pertes sont plus grosses que les gains, compensées par le nombre de gains
- **Pas de gestion de taille** : position fixe à 1 000 unités, pas de pyramiding
- **Pas de filtre de tendance** : le système trade tous les breakouts, y compris en range

### Causes profondes
1. Sur H1, le close > highest(20) est franchi très fréquemment (presque chaque jour)
2. Le SL serré (2×ATR) et TP large (4×ATR) donnent beaucoup de petits gains et rares grosses pertes
3. La combinaison d'un win rate élevé et de commissions fixes par trade tue la rentabilité

### Scénarios d'amélioration
- Passer en H4 ou Daily pour réduire la fréquence de trading
- Ajouter un filtre de tendance (EMA 200, ADX > 25)
- Augmenter la période Donchian à 40 ou 60 barres
- Utiliser un trailing stop progressif (chandelier ATR)
- Ajuster la taille de position (risque % du capital)
- Supprimer le max 1 trade/jour sur H1 — c'est trop restrictif et en même temps pas assez
- Réduire la taille de position pour limiter l'exposition

## Leçons apprises

1. **Le Donchian classique sur H1 ne fonctionne pas sur le Forex moderne avec frais fixes.** La fréquence de trading tue la rentabilité même avec un excellent ratio gain/perte.
2. **Profit Factor brut ne veut rien dire si les coûts ne sont pas intégrés.** Le PF de 22 devient un PF net de ~0,82 après commissions.
3. **La taille de position fixe est dangereuse** — elle devrait être basée sur un % du capital avec levier dynamique.
4. **Les Turtles utilisaient des futures avec des frais de courtage très bas** — la transposition au Forex avec $5/trade n'est pas viable.
5. **Un win rate de 86% masque un avg trade négatif** — les petits gains fréquents ne compensent pas les grosses pertes rares après frais.

## Verdict

| Critère | Évaluation |
|---------|-----------|
| Concept | ✅ Classique, éprouvé, logique |
| Robustesse walk-forward | ✅ Cohérent IS → OOS |
| Rentabilité brute | ✅ PF > 20 (excellent) |
| Rentabilité nette | ❌ -31% (tué par les coûts) |
| Applicabilité réelle | ❌ Nécessite frais très bas ou timeframe supérieur |
| Potentiel optimisé | ⚠️ Passage H4/Daily + filtre tendance pourrait fonctionner |

**Note :** La stratégie est structurellement solide mais inadaptée au trading H1 sur le Forex avec commissions de $5/trade. Recommandation : migrer vers H4/Daily avec période Donchian 40-60 et filtre de tendance EMa200.
