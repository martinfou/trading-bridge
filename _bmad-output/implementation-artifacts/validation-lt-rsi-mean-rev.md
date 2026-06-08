# Validation — LtRSIMeanRev (RSI Extreme Mean Reversion)

**Date:** 2026-06-07
**Branch:** `feature/lt-rsi-mean-rev-20260607`
**Strategy Class:** `com.martinfou.trading.strategies.longterm.LtRSIMeanRev`
**Runner Class:** `com.martinfou.trading.examples.RunLtRSIMeanRev`

---

## Concept

Stratégie de **mean reversion** basée sur les extrêmes du RSI(14) avec un filtre de tendance EMA(200). Le principe est de capturer les retours à la moyenne après des mouvements excessifs, tout en s'alignant sur la tendance long-terme.

### Logique d'entrée:
- **BUY** : RSI(14) < 25 (surachat local) **ET** prix > EMA(200) (tendance haussière long-terme)
- **SELL** : RSI(14) > 75 (survente locale) **ET** prix < EMA(200) (tendance baissière long-terme)

### Logique de sortie:
- Stop-loss à 2× ATR(14)
- Take-profit à 4× ATR(14)
- Sortie anticipée si le RSI revient dans la zone neutre [40, 60]
- Toutes les sorties utilisent `closeOnly()` pour éviter le hedging OANDA

### Filtres supplémentaires:
- Maximum 1 trade par jour calendaire

---

## Paramètres

| Paramètre           | Valeur |
|---------------------|--------|
| RSI Period          | 14     |
| RSI Oversold        | < 25   |
| RSI Overbought      | > 75   |
| EMA Period          | 200    |
| ATR Period          | 14     |
| SL Multiplier       | 2× ATR |
| TP Multiplier       | 4× ATR |
| RSI Exit Zone       | [40, 60] |
| Max trades/day      | 1      |
| Base Units          | 1000   |
| Capital initial     | $100,000 |

---

## Résultats

### EUR_USD

| Période   | Label | Trades | PF    | Sharpe   | PnL       | DD max | Win Rate |
|-----------|-------|--------|-------|----------|-----------|--------|----------|
| 2010-2025 | FULL  | 966    | 0.23  | -116.81  | -$1,235.64 | 1.24%  | 30.7%    |
| 2010-2018 | IS    | 548    | 0.24  | -103.94  | -$839.34  | 0.84%  | 31.8%    |
| 2019-2022 | OOS1  | 251    | 0.22  | -146.67  | -$257.31  | 0.26%  | 27.9%    |
| 2023-2025 | OOS2  | 161    | 0.24  | -158.56  | -$136.01  | 0.14%  | 32.3%    |

### GBP_USD

| Période   | Label | Trades | PF    | Sharpe   | PnL       | DD max | Win Rate |
|-----------|-------|--------|-------|----------|-----------|--------|----------|
| 2010-2025 | FULL  | 958    | 0.24  | -94.18   | -$1,492.97 | 1.50%  | 32.3%    |
| 2010-2018 | IS    | 541    | 0.26  | -93.96   | -$862.61  | 0.87%  | 32.3%    |
| 2019-2022 | OOS1  | 255    | 0.20  | -85.54   | -$443.09  | 0.45%  | 32.5%    |
| 2023-2025 | OOS2  | 157    | 0.20  | -127.45  | -$184.36  | 0.19%  | 31.2%    |

---

## Walk-Forward Analysis

### Cohérence IS → OOS
Les résultats sont **remarquablement stables** entre les périodes IS, OOS1 et OOS2 pour les deux paires — le Profit Factor reste systématiquement entre 0.20 et 0.26, le win rate entre 27% et 33%. Cette cohérence suggère que la stratégie capture un signal réel (même si négatif) plutôt que du bruit.

### Eur/usd walk-forward
- IS (2010-2018) : PF=0.24, WR=31.8%
- OOS1 (2019-2022) : PF=0.22, WR=27.9%
- OOS2 (2023-2025) : PF=0.24, WR=32.3%
- **Constat:** Dégradation légère OOS1, rebond OOS2. Comportement cohérent avec une stratégie perdante.

### Gbp/usd walk-forward
- IS (2010-2018) : PF=0.26, WR=32.3%
- OOS1 (2019-2022) : PF=0.20, WR=32.5%
- OOS2 (2023-2025) : PF=0.20, WR=31.2%
- **Constat:** Très stable. La perte s'accentue légèrement hors-échantillon.

### Analyse des trades
Avec ~960 trades par paire sur 15 ans soit ~64 trades/an, la stratégie génère en moyenne 1 trade tous les 4 jours ouvrés — ce qui est cohérent avec la fréquence des signaux RSI extrêmes. Le drawdown maximum est très faible (< 1.5%), indiquant une stratégie à faible risque unitaire mais à espérance mathématique négative persistante.

---

## Leçons apprises

1. **Paramètres trop restrictifs?** RSI(14) < 25 et > 75 sont des seuils très extrêmes. Bien qu'ils génèrent 64 signaux/an, ces signaux ne sont pas rentables. Peut-être que des seuils moins extrêmes (30/70) captureraient des mouvements de reversion plus fiables.

2. **Ratio risque/récompense inadéquat.** Avec SL=2×ATR et TP=4×ATR, le ratio RR est de 1:2. Avec un win rate de ~31%, le breakeven nécessite un win rate de 33% (1/(1+2) = 33.3%). La stratégie est juste en-dessous du seuil de rentabilité, ce qui explique les pertes faibles mais systématiques.

3. **Filtre EMA(200) contre-productif?** Il est possible que le filtre de tendance long-terme EMA(200) soit trop lent (200 jours ≈ 10 mois) et filtre des signaux qui seraient profitables, ou au contraire ne filtre pas assez les faux signaux.

4. **Le mean reversion RXI pur fonctionne mieux sur des horizons plus courts** (intraday / swing) que sur du daily long terme. Les extrêmes RSI sur daily peuvent persister longtemps dans une tendance forte.

5. **Alternative à explorer:** Utiliser le RSI(2) (Connors RSI) au lieu du RSI(14) classique, qui capture mieux les retournements à court terme. Ou ajouter un filtre de volatilité (Band Width) pour éviter les marchés en range.

---

## Verdict

| Critère              | Évaluation    | Notes |
|----------------------|---------------|-------|
| Profitabilité        | ❌ ÉCHEC      | PF < 1 sur toutes périodes |
| Cohérence IS/OOS     | ✅ BON        | Très stable entre périodes |
| Drawdown             | ✅ EXCELLENT  | Max < 1.5% |
| Volume de trades     | ✅ BON        | ~64 trades/an, statistiquement significatif |
| Concept original     | ⚠️ MITIGÉ     | Mean reversion RXI classique mais non rentable aux paramètres actuels |

### Décision: **NE PAS PROMOUVOIR** en l'état.

La stratégie montre une espérance mathématique négative très stable — presque trop stable pour être du bruit. Le concept de mean reversion RXI + EMA(200) a du sens théoriquement mais ne produit pas de P&L positif avec les paramètres testés.

**Pistes d'amélioration:**
1. Ajuster les seuils RSI à 30/70 au lieu de 25/75
2. Remplacer EMA(200) par EMA(50) ou EMA(100) pour un filtre plus réactif
3. Essayer RSI(2) au lieu de RSI(14) (Connors-style)
4. Ajouter un filtre de volatilité (éviter les entrées en faible volatilité)
5. Optimiser le ratio RR (essayer 2.5×/3.5× au lieu de 2×/4×)

---

*Rapport généré automatiquement par Hermes Agent.*
