# Modèle de coûts réel — preuve contre le courtier + re-baseline des 5 stratégies déployées

Date : 2026-10-01 — branche `research/cost-model-real` — compte OANDA practice `101-002-4729622-014` (CAD).

---

## (a) Modèle retenu et constante autoritaire

### Autoritaire — `RealCostModel` (nouveau, `trading-backtest/.../backtest/RealCostModel.java`)

Un seul modèle, mesuré depuis le courtier (lecture seule), deux coûts réels, **zéro commission** :

| Élément | Source | Constante (fichier:ligne) |
|---|---|---|
| Spread | médiane bid/ask, 500 bougies H1 `price=BA` | `RealCostModel.HALF_SPREAD_PRICE` (RealCostModel.java:37) |
| Swap | `financing.longRate`/`shortRate` (fraction annuelle) | `RealCostModel.FINANCING_ANNUAL` (RealCostModel.java:50) |
| Formule swap | `swap[pips/jour] = taux × mid / (pipSize × 365)` | `RealCostModel.swapPipsPerDay` (RealCostModel.java:107) |
| pipSize | JPY et métaux = 0.01 ; autres FX = 0.0001 | `RealCostModel.pipSize` (RealCostModel.java:86) |
| Profil de coût | commission 0, demi-spread/jambe, stopSlippagePct | `RealCostModel.costFor` (RealCostModel.java:123) |

### Abandonnée — `BacktestExecutionCost.DEFAULT`

`BacktestExecutionCost.java:32-33` — `@Deprecated` : commission **0.07 $** + `slippageFixed` **0.00005**.
Le 0.07 $ n'existe pas sur ces comptes (spread-only), et 0.00005 en delta-prix sous-estime le spread
~100× sur JPY et ~10 000× sur l'or. La table `SwapCalculator.SWAP_RATES` (SwapCalculator.java:24-46)
avait **3 signes faux** (GBP_JPY, AUD_USD, USD_CAD) et l'or **32× trop petit** (−2.0 au lieu de −65.2).

---

## (b) Preuve de justesse contre le courtier

### 1. Ancrage swap — AUD_USD

`financing.longRate` AUD_USD = **−0.0040** (−0.40 %/an), exactement le différentiel de taux
AUD−USD attendu ⇒ l'API publie bien une **fraction annuelle**.
`swapPipsPerDay(−0.0040, 0.692945, AUD_USD) = −0.0040 × 0.692945 / (0.0001 × 365) = −0.076 pip/j`
≈ **−0.08** (FEE-AUDIT §4). ✓

### 2. Conversion pas à pas (devise du compte = CAD, USD_CAD = 1.4223)

- **EUR_USD** (non-JPY, quote USD) : notional = 100 000 × 1.12456 = 112 456 USD →
  swap/jour = −0.0248 × 112 456 / 365 = **−7.64 USD/jour → ×1.4223 = −10.87 CAD/jour**
  (en pips : pipValue = 10 USD → −0.76 pip/jour, cohérent avec FEE-AUDIT −0.77).
- **GBP_JPY** (quote JPY) : notional = 100 000 × 208.433 = 20 843 300 JPY →
  swap/jour = 0.0156 × 20 843 300 / 365 = 890.84 JPY/jour → ÷157.925 = **5.64 USD/jour → ×1.4223 = +8.02 CAD/jour**
  (en pips : pipValue = 0.01×100000/157.925 = 6.33 USD → +0.89 pip/jour ; l'ancienne table disait **−4.5**, signe inversé).
- **XAU_USD** (métal, lot = 100 oz) : notional = 100 × 4182.07 = 418 207 USD →
  swap/jour = −0.0569 × 418 207 / 365 = **−65.19 USD/jour → ×1.4223 = −92.73 CAD/jour**
  (en pips : pipValue = 0.01×100 = 1 USD → −65.2 pip/jour ; l'ancienne table disait **−2.0**, 32× trop petit).

### 3. Vérification aller-retour contre le courtier

Les 13 taux annuels convertis par la formule tombent tous dans ±0.03 pip/jour des valeurs
indépendantes publiées dans FEE-AUDIT §4 (EUR −0.76/−0.77, GBP_JPY +0.89/+0.89, USD_JPY +0.78/+0.78,
USD_CHF +0.72/+0.72, XAU −65.2/−64.75, USD_CAD +0.26/+0.26, AUD −0.08/−0.08).

Tests unitaires : `SwapCalculatorTest` (8) + `RealCostModelTest` (4) + `BacktestExecutionCostTest` (3)
= **15 verts** (verrouillent l'ancrage AUD_USD, le pipSize métal, et la correction des signes).

---

## (c) Tableau avant / après (5 stratégies, fenêtres FULL/IS/OOS1/OOS2, capital 10 000)

« avant » = `DEFAULT` (comm 0.07 + slip 0.00005) + swap legacy. « après » = `RealCostModel` + swap corrigé.

### consecbar — GBP_JPY (3650 trades)

| Fenêtre | PF avant | Sharpe av. | DD% av. | PF après | Sharpe ap. | DD% ap. |
|---|---|---|---|---|---|---|
| FULL | 0.97 | −8.47 | 8.02 | **0.80** | −8.50 | 8.78 |
| IS | 0.97 | −8.32 | 4.41 | 0.80 | −8.35 | 4.81 |
| OOS1 | 1.01 | −9.37 | 1.88 | 0.81 | −9.35 | 1.93 |
| OOS2 | 0.92 | −8.71 | 2.14 | 0.76 | −8.88 | 2.45 |

### vwpreversion — USD_CHF : **0 trade sur toutes les fenêtres** (voir §f)

### monthweekphase — USD_JPY (419 trades)

| Fenêtre | PF avant | Sharpe av. | DD% av. | PF après | Sharpe ap. | DD% ap. |
|---|---|---|---|---|---|---|
| FULL | 0.93 | −10.83 | 1.50 | **0.87** | −11.01 | 1.55 |
| IS | 0.95 | −10.99 | 0.59 | 0.88 | −11.24 | 0.73 |
| OOS1 | 1.47 | −6.74 | 0.91 | 1.36 | −7.25 | 0.81 |
| OOS2 | 0.73 | −9.79 | 1.04 | 0.69 | −9.89 | 1.04 |

### compmomentum — USD_JPY (757 trades)

| Fenêtre | PF avant | Sharpe av. | DD% av. | PF après | Sharpe ap. | DD% ap. |
|---|---|---|---|---|---|---|
| FULL | 1.05 | −3.26 | 5.26 | **1.02** | −3.41 | 4.66 |
| IS | 0.86 | −3.64 | 5.26 | 0.84 | −3.86 | 4.66 |
| OOS1 | 1.41 | −3.87 | 0.83 | 1.36 | −3.98 | 0.76 |
| OOS2 | 1.34 | −2.00 | 0.72 | 1.30 | −2.00 | 0.67 |

### ltrsi3 — EUR_USD (4631 trades)

| Fenêtre | PF avant | Sharpe av. | DD% av. | PF après | Sharpe ap. | DD% ap. |
|---|---|---|---|---|---|---|
| FULL | 0.84 | 0.24 | 249.62 | **0.76** | 0.38 | 381.56 |
| IS | 0.88 | 0.31 | 127.41 | 0.81 | 0.47 | 187.71 |
| OOS1 | 0.72 | −0.08 | 119.44 | 0.64 | 0.16 | 160.48 |
| OOS2 | 0.88 | −0.91 | 40.11 | 0.79 | −1.57 | 60.09 |

---

## (d) Verdict par stratégie (règles de la porte : PF ≥ 1.05, Sharpe ≥ 0.3, DD ≤ 35 %, OOS PF < 1.0 = invalide)

> **Avertissement Sharpe** : le `BacktestResult.sharpeRatio()` du moteur est un Sharpe **par bougie H1
> annualisé sur l'équité flottante** (×√6240), pas un Sharpe quotidien/trade. Il est incohérent avec le PF
> (ex. compmomentum OOS1 PF 1.41 → Sharpe −3.87 ; ltrsi3 qui perd 25 k$ → Sharpe +0.24). Aucune stratégie ne
> franchit « Sharpe ≥ 0.3 » sur cette métrique, quel que soit son PF. Le verdict s'appuie donc sur **PF** et **DD** ;
> le Sharpe du moteur est signalé mais **non exploitable** pour la porte (voir §f).

1. **consecbar (GBP_JPY)** — **ne survit pas.** PF après FULL 0.80 (< 1.05) ; OOS1 0.81 et OOS2 0.76 < 1.0 → **invalide**.
   Coût : prix pur −98.61 $, spread −678.57 (91 %), swap −63.38 (9 %). Le spread GBP_JPY (3.4 pips, le plus large)
   × 3650 trades est le tueur ; le swap est mineur.

2. **vwpreversion (USD_CHF)** — **non établi.** 0 trade : la stratégie exige un pic de volume
   (`volumeRatio > 1.3`) et les CSV Dukascopy du dépôt sont bid-only **sans colonne volume**. PF/Sharpe indéfinis (0.00).

3. **monthweekphase (USD_JPY)** — **ne survit pas.** PF après FULL 0.87 (< 1.05) ; OOS2 0.69 < 1.0 → **invalide**.
   Coût : prix pur −59.24 $, spread −58.93 (90 %), swap −6.64 (10 %). Spread-dominé.

4. **compmomentum (USD_JPY)** — **le plus proche, mais échoue.** OOS1 1.36 et OOS2 1.30 **survivent aux coûts**
   (PF > 1.05). Mais FULL 1.02 < 1.05, plombé par un IS (2010-2018) à 0.84. Le swap corrigé **aide** ici
   (net avant −144.72 → après −9.23, car le swap legacy surchargait le short USD_JPY ~5×). Coût : prix pur +143.73,
   spread −91.61 (60 %), swap −61.34 (40 %). La porte stricte (FULL ≥ 1.05) est manquée de 0.03.

5. **ltrsi3 (EUR_USD)** — **ne survit pas, de loin.** PF après FULL 0.76, DD 381.56 % (> 35 %), OOS PF < 1.0 → **invalide**.
   Était déjà en échec avant les coûts (PF 0.84). Coût : prix pur −4568.36, spread −34246 (100 %), swap −87 (≈0 %).
   4631 trades × ~46 k unités × 1.6 pip = ~34 k$ de spread sur 16 ans, sur un capital de référence fixe de 10 k$.

---

## (e) SHA poussé

`git push -u origin research/cost-model-real` — SHA reporté après le commit (voir `git rev-parse HEAD`).

---

## (f) Non établi

1. **vwpreversion — PF/Sharpe non établis** (0 trade). Cause : entrée conditionnée à un pic de volume, données
   historiques bid-only sans volume. Pour l'obtenir : alimenter des bougies H1 avec volume (candles OANDA `price=M`
   avec volume, ou un flux Dukascopy ask+volume), puis rejouer.
2. **Sharpe de la porte** : le Sharpe du moteur (par bougie H1, équité flottante) ne permet pas d'appliquer
   « Sharpe ≥ 0.3 » — il est incohérent avec PF/net (PF 1.41 → Sharpe −3.87 ; perte 25 k$ → Sharpe +0.24).
   Il faudrait un Sharpe quotidien ou par trade pour rendre la porte Sharpe exploitable.
3. **La saisonnalité USD_CAD (PF 5.73 → IS +226 / OOS −448)** citée en contexte n'est pas rejouée ici : hors périmètre
   (les 5 déployées seulement), leçon déjà établie.
4. **Fenêtre de 30 jours** : ce que mesure la fenêtre paper ne peut être relié au backtest qu'après accumulation de
   trades réels ; au jour 1, aucune transaction n'est encore dans la fenêtre (`docs/paper-window-log.md`).

---

## Fichiers modifiés

- `trading-backtest/.../backtest/RealCostModel.java` (nouveau, autoritaire)
- `trading-backtest/.../backtest/SwapCalculator.java` (table corrigée, pipSize métal, normalisation des underscores)
- `trading-backtest/.../backtest/BacktestExecutionCost.java` (`DEFAULT` déprécié)
- `trading-backtest/.../backtest/BacktestEngine.java` (javadoc `slippageFixed` = delta-prix)
- `trading-backtest/src/test/.../SwapCalculatorTest.java` (valeurs corrigées)
- `trading-backtest/src/test/.../RealCostModelTest.java` (nouveau)
- `trading-examples/.../examples/RunCostModelReal.java` (runner avant/après + preuve)
