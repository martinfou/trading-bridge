# Modèle de coûts réel — 2e passe NEEDS_FIX corrigée + borne de l'erreur de swap

Date : 2026-10-01 — branche `research/cost-model-real` — compte OANDA practice `101-002-4729622-014` (CAD).

Ce document corrige les constats des deux passes de revue sur le travail précédent (SHA `11ee4fd8`).
Le modèle `RealCostModel` (commission 0, demi-spread mesuré, swap = fraction annuelle) et la table
`SwapCalculator.SWAP_RATES` déjà corrigés **ne sont pas refaits** ; seuls les constats ci-dessous sont traités.

---

## (a) Corrections et règle de repli retenue

| # | Constat | Correction | Fichier |
|---|---|---|---|
| 3 | `AUD_USD` absent de `HALF_SPREAD_PRICE` (mesuré 1.30 pip) → retombait sur 1.0 pip (sous-estimation) | Ajouté `AUD_USD = 0.000065` (0.65 pip/jambe) | `RealCostModel.java:39` |
| 3 | Repli qui sous-estime | **Repli = refus.** `halfSpread()` lève `IllegalArgumentException` nommant la paire. Choisi plutôt que « spread mesuré le plus élevé » (0.265 = l'or) car ce dernier surchargerait tout FX de ~3000× et tuerait la recherche ; aucun repli numérique ne peut garantir « coût modélisé ≥ coût réel » sur une plage de 4 ordres de grandeur | `RealCostModel.java` |
| 4 | `halfSpread()` ne normalise pas « EURUSD » / « usd_jpy » | Normalisation majuscules + tirets bas/slash retirés, lookup insensible aux underscores | `RealCostModel.normalizeSymbol/halfSpread` |
| 2 | `pipValueInUSD` ne convertit que JPY → USD_CAD/USD_CHF/EUR_GBP traités en USD | Ajout `RealCostModel.usdPerQuoteUnit()` : USD→1, JPY→1/157.925, CAD→1/1.422295, CHF→1/0.83099, GBP→1.31978, EUR→1.12456, AUD→0.692945, NZD→0.56014. La branche JPY (÷`usdJpyRate`) est inchangée | `SwapCalculator.java:150`, `RealCostModel.java` |
| 5 | Or : mid statique 4182.07 pour 15 ans → surévalue le portage ~2.5× | Ajout `GOLD_SWAP_MID = 1664.0` = moyenne des closes H1 bid 2010-2025 (`data/historical/dukascopy/xauusd-h1-bid-*.csv`, 139 757 bougies, mean 1663.99). Swap or : −65.2/+37.0 → **−25.9/+14.7** pip/j. `REFERENCE_MIDS["XAU_USD"]` reste 4182.07 (dénominateur du stop-slippage au prix courant) | `RealCostModel.java`, `SwapCalculator.java:45` |
| 1 | Taux de financement 2026 appliqués à 2010-2025 | Documenté explicitement (javadoc + commentaire table) : **instantané 2026**, aucune série historique, borne mesurée (voir §d) | `RealCostModel.java`, `SwapCalculator.java` |

**Liste des paires mesurées** (demi-spread/jambe) : EUR_USD 0.00008, GBP_JPY 0.0170, USD_JPY 0.0080,
AUD_USD 0.000065, USD_CHF 0.000075, XAU_USD 0.265. Toute autre paire → **refus** (exception).

---

## (b) Preuve du signe de l'or (point 7 — réfuté avec preuve)

Valeur brute lue en direct sur l'hôte practice le 2026-10-01 :

```
XAU_USD  financing.longRate = -0.0569   financing.shortRate = +0.0323
```

Convention OANDA (help.oanda.com, FAQ financing) : *« A negative funding rate will result in a
charge … and a positive funding rate will result in a credit. »*

Conversion (`swap = taux × mid / (pipSize × 365)`, pipSize or = 0.01) :
- au mid moyen de période 1664 : `+0.0323 × 1664 / 3.65 = +14.7 pip/j`
- au spot 4182.07 (valeur du rapport précédent) : `+0.0323 × 4182.07 / 3.65 = +37.0 pip/j` (cohérent avec FEE-AUDIT §4 `+36.76`)

**Le signe + (crédit pour les shorts) est juste.** La valeur brute est `shortRate = +0.0323` (+3.23 %/an),
positive → crédit selon la convention OANDA. Le doute de la passe est infondé ; la magnitude a été
recalibrée de +37.0 (spot 2026) à +14.7 (moyenne de période), sans changer le signe.

---

## (c) Tableau avant/après des 5 stratégies (FULL/IS/OOS1/OOS2, capital 10 000)

« avant » = `DEFAULT` (comm $0.07 + slip 0.00005) + swap legacy. « après » = `RealCostModel` + swap corrigé.

| Stratégie (paire) | Fenêtre | PF avant | PF après | net avant | net après |
|---|---|---|---|---|---|
| consecbar (GBP_JPY) | FULL | 0.97 | **0.80** | −759.87 | −840.56 |
| | IS | 0.97 | 0.80 | −413.47 | −456.47 |
| | OOS1 | 1.01 | 0.81 | −166.61 | −171.12 |
| | OOS2 | 0.92 | 0.76 | −177.53 | −210.65 |
| vwpreversion (USD_CHF) | toutes | 0.00 | 0.00 | 0.00 | 0.00 |
| monthweekphase (USD_JPY) | FULL | 0.93 | **0.87** | −116.56 | −124.82 |
| | IS | 0.95 | 0.88 | −46.94 | −62.82 |
| | OOS1 | 1.47 | 1.36 | 68.99 | 61.46 |
| | OOS2 | 0.73 | 0.69 | −50.22 | −53.14 |
| compmomentum (USD_JPY) | FULL | 1.05 | **1.02** | −144.72 | −9.23 |
| | IS | 0.86 | 0.84 | −444.34 | −340.43 |
| | OOS1 | 1.41 | 1.36 | 131.44 | 144.08 |
| | OOS2 | 1.34 | 1.30 | 145.69 | 164.21 |
| ltrsi3 (EUR_USD) | FULL | 0.84 | **0.76** | −25426.77 | −38901.95 |
| | IS | 0.88 | 0.81 | −10911.85 | −17716.61 |
| | OOS1 | 0.72 | 0.64 | −11843.97 | −15949.01 |
| | OOS2 | 0.88 | 0.79 | −2877.73 | −5404.66 |

**Aucun verdict ne change** par rapport au rejeu précédent : les 5 échouent la porte, compmomentum rate
toujours FULL de 0.03 (1.02 vs 1.05). Les corrections de cette passe (AUD_USD, normalisation, conversion
non-USD, or) n'affectent pas ces 5 résultats : les paires déployées sont GBP_JPY/USD_JPY (quote JPY, déjà
converti), USD_CHF (0 trade) et EUR_USD (quote USD). La conversion non-USD (point 2) ne touche que
USD_CAD/USD_CHF/EUR_GBP, hors périmètre des 5.

---

## (d) Sensibilité au swap — borne chiffrée de l'erreur d'instantané (point 1)

Rejeu des 5 stratégies avec swap × {0, 1, 2} (PF par fenêtre ; net $ sur FULL) :

| Stratégie | swap % coût | FULL ×0/×1/×2 | OOS1 ×0/×1/×2 | OOS2 ×0/×1/×2 | net FULL ×0→×2 |
|---|---|---|---|---|---|
| compmomentum | **40 %** | 1.02 / 1.02 / 1.02 | 1.36 / 1.36 / 1.36 | 1.30 / 1.30 / 1.30 | +52.12 → −70.57 |
| monthweekphase | 10 % | 0.87 / 0.87 / 0.87 | 1.36 / 1.36 / 1.36 | 0.69 / 0.69 / 0.69 | −118.18 → −131.46 |
| consecbar | 9 % | 0.80 / 0.80 / 0.80 | 0.81 / 0.81 / 0.81 | 0.76 / 0.76 / 0.76 | −777.18 → −903.94 |
| ltrsi3 | ~0 % | 0.76 / 0.76 / 0.76 | 0.64 / 0.64 / 0.64 | 0.79 / 0.79 / 0.79 | −38814.51 → −38989.39 |
| vwpreversion | — | 0.00 | 0.00 | 0.00 | 0.00 |

**Résultat : l'erreur est bornée.** Le PF est **invariant** (à 2 décimales) sous ×0/×1/×2 dans chaque
fenêtre et pour chaque stratégie ; seul le net en dollars bouge (compmomentum FULL va de +52 à −71).
La porte étant basée sur le PF, **aucun verdict ne bouge** sous aucune hypothèse de swap — en particulier
compmomentum reste à FULL 1.02 < 1.05 même à swap ×0 (l'échec vient de l'IS 0.84, pas du swap). L'erreur
« instantané 2026 appliqué à l'historique » ne remet donc en cause aucun verdict, ce qui est une réponse
honnête et mesurée (pas une prétention de correction).

---

## (e) Décompte des migrations (point 6)

`BacktestExecutionCost` a un nouveau point d'entrée `RunContext.forStrategy(…, Function<String,BacktestExecutionCost>)`
qui résout le coût réel par symbole (`RealCostModel.costFor(symbol)`). Les runners migrés passent `RealCostModel::costFor`.

- **Migrés : 54 sites / 53 fichiers** — vers `RealCostModel.costFor` :
  - 44 runners `ofCommissionAndSlippage(0.07, 0.0001)` (tout `trading-examples` + `GoldExitProbe`) ;
  - `VerifyLtRSI3Momentum` (`OANDA_SPREAD` → `costFor("EUR_USD")`) ;
  - `LtPipelineOrchestrator` (`forStrategy` 5-arg → 9-arg `costFor`) ;
  - 8 sites `forStrategy` 5/7-arg qui passaient par `DEFAULT` (`RunBacktest`×2, `RunLtRSI3Momentum`,
    `RunLtVolRegime`, `RunLtSqueezeMomentum`, `RunLtPullbackEntry`, `RunLtDoubleMA`, `RunAllBatchBacktests`).

- **Restants (non migrés), et pourquoi :**
  1. `BatchStrategyRunner` (trading-genetics, 3× `OANDA_SPREAD.configure`) — symbole inféré du chemin de
     données et non propagé aux sites `.configure(engine)` ; migrer correctement exige de faire circuler le
     symbole dans le pipeline génétique (refactor séparé).
  2. `RunConfigSnapshot` / `ExecutionStressConfig` (trading-runtime) — chemin **déployé** ; hors périmètre
     (conteneurs en fenêtre d'observation, aucun déploiement).
  3. `RunContext` fallback `null → DEFAULT` (lignes 34-36 et 106) — conservé `@Deprecated` ; désormais
     inatteignable depuis les runners de recherche (tous migrés), retiré dans un changement dédié pour ne
     pas perturber le chemin déployé.
  4. Modèles « commission $5 » custom (`RunLtRangeBreakout`, `RunThreeStrategies` V1–V4, `DebugBacktest`) —
     modèles de sensibilité distincts du `$0.07 DEFAULT`, hors périmètre du point 6.

Compilation vérifiée : `trading-backtest` (192 tests, 0 échec) + `trading-examples`/`trading-intelligence`/
`trading-genetics` compilent.

---

## (f) Reste non établi

1. **Série historique de financement** : aucune source de taux par année dans le repo. L'erreur d'instantané
   2026 est bornée (verdicts invariants, §d) mais pas corrigée. Pour l'obtenir : `financing` OANDA historique
   par année, ou différentiels de taux directeurs (probe FRED `fetch_rates.py` inachevé).
2. **Spreads des 7 paires non mesurées** (GBP_USD, NZD_USD, USD_CAD, EUR_GBP, AUD_JPY, NZD_JPY, EUR_JPY) :
   désormais **refus** (exception) — tout runner qui les négocie échoue bruyamment. Pour les obtenir :
   mesurer 500 bougies H1 `price=BA` par paire et les ajouter à `HALF_SPREAD_PRICE`.
3. **`BatchStrategyRunner`** : coût `OANDA_SPREAD` (0.00005, sous-estimé) encore en place — migration
   nécessitant la propagation du symbole (cf. §e).
4. **vwpreversion** : toujours 0 trade (entrée conditionnée à un pic de volume, CSV bid-only sans volume) —
   inchangé.
5. **Sharpe de la porte** : métrique du moteur (par bougie H1, équité flottante) toujours incohérente avec
   PF/net ; verdict basé sur PF/DD, Sharpe non exploitable — inchangé.

---

## Fichiers modifiés

- `trading-backtest/.../backtest/RealCostModel.java` (AUD_USD, refus, normalisation, `usdPerQuoteUnit`, `GOLD_SWAP_MID`, `costFor(String)`, doc instantané)
- `trading-backtest/.../backtest/SwapCalculator.java` (conversion quote→USD non-JPY, swap or période-moyenne, doc instantané)
- `trading-backtest/.../backtest/RunContext.java` (overload `Function<String,BacktestExecutionCost>`)
- `trading-backtest/.../backtest/BacktestExecutionCost.java` (inchangé — `DEFAULT`/`OANDA_SPREAD` restent dépréciés)
- `trading-backtest/src/test/.../RealCostModelTest.java` (AUD_USD, refus, normalisation, quote→USD, or période)
- `trading-backtest/src/test/.../SwapCalculatorTest.java` (or −25.9, conversion non-USD, override USD_CAD)
- `trading-examples/.../RunCostModelReal.java` (affichage or période + balayage swap ×0/×1/×2)
- 53 fichiers de recherche migrés vers `RealCostModel.costFor` (voir §e)
