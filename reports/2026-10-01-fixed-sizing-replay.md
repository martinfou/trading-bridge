# Rejeu D37 — sizing fixe (1 % de risque, capital 10 000 $) des 3 candidats or + 5 déployées

Date : 2026-10-01 — branche `research/fixed-sizing-1pct` — worktree dédié `sizing-wt`.

---

## Contexte et méthode

La décision D37 impose : **tous les backtests de porte se font à sizing fixe, 1 % de risque par
transaction, capital identique 10 000 $** (au lieu de 50 000 $ avec le sizing propre de chaque
recherche pour le shortlist, et du sizing interne fixe pour les déployées). Le modèle de coûts est
`RealCostModel` (commission 0, demi-spread mesuré par jambe, swap = fraction annuelle), aux **deux
niveaux de spread** demandés : **médiane mesurée** et **conservateur ×1,5**, comme le rejeu du
shortlist.

**Règle de sizing appliquée** : `units = (0.01 × capital) / distanceAuStop`, capital = 10 000 $,
risque = 100 $ par transaction. Dénominateur = le stop de l'ordre s'il existe (champ interne / stop
ATR), sinon un **stop ATR(14) inventé (1×)** — les stratégies concernées sont nommées explicitement.
Le stop ne sert que de **dénominateur** du budget de risque : il ne modifie pas la logique de sortie
dans le rejeu. L'effet d'un stop réellement *attaché* est mesuré séparément (preuve MAE, §c).

**Validation du harnais (reproduction au centime)** : le runner rejoue d'abord les stratégies à leur
taille de référence et reproduit exactement les chiffres publiés — gold dualgate PF 1.32 / 683 trades
/ +12 217 $, turtle_dxy 1.28 / 674 / +10 849 $, weekday_dxy 1.28 / 471 / +4 022 $ ; consecbar 0.80,
monthweekphase 0.87, compmomentum 1.02, ltrsi3 0.76, vwpreversion 0 trade. **Aucun chiffre ci-dessous
n'est inventé ; tout est sorti du moteur.**

> ⚠️ **Devise du net** : le moteur calcule le net en **devise de cotation** de l'instrument. Pour l'or
> (XAU_USD) et EUR_USD, c'est de l'USD (interprétation directe). Pour GBP_JPY / USD_JPY c'est du **JPY**,
> pour USD_CHF du **CHF**. Le DD% des paires JPY-quotées mélange donc un capital USD avec un PnL JPY —
> c'est un artefact d'unité, pas un chiffre de risque exploitable. La porte est jugée sur **PF et DD%**
> (et « OOS PF < 1.0 = invalide ») ; pour l'or, ces deux métriques sont cohérentes (USD/USD).

---

## (a) Tableau complet — PF / DD% / trades / net (médiane | ×1,5)

### Candidat 1 — Gold Turtle 55/20 + double porte OR (`gold_dualgate`, XAU_USD)

| Fenêtre | PF méd. | DD% méd. | TR | NET$ méd. | PF ×1.5 | DD% ×1.5 | NET$ ×1.5 |
|---|---|---|---|---|---|---|---|
| FULL | 1.32 | **47.85** | 683 | +30 824 | 1.27 | **55.13** | +24 914 |
| IS   | 1.15 | 47.85 | 387 | +5 410 | 1.09 | 55.13 | +1 740 |
| OOS1 | 1.26 | 38.55 | 169 | +5 920 | 1.21 | 41.77 | +4 476 |
| OOS2 | 1.98 | 49.77 | 127 | +19 565 | 1.92 | 51.53 | +18 782 |

### Candidat 2 — Gold Turtle + DXY opposé (`gold_turtle_dxy`, XAU_USD)

| Fenêtre | PF méd. | DD% méd. | TR | NET$ méd. | PF ×1.5 | DD% ×1.5 | NET$ ×1.5 |
|---|---|---|---|---|---|---|---|
| FULL | 1.25 | **49.57** | 674 | +24 124 | 1.20 | **60.20** | +18 420 |
| IS   | 1.13 | 45.06 | 381 | +5 144 | 1.07 | 50.79 | +1 535 |
| OOS1 | 1.22 | 44.49 | 170 | +5 156 | 1.17 | 48.06 | +3 562 |
| OOS2 | 1.61 | 62.70 | 124 | +12 182 | 1.57 | 64.66 | +11 470 |

### Candidat 3 — Gold vendredi long × DXY opposé (`gold_weekday_dxy`, XAU_USD)

| Fenêtre | PF méd. | DD% méd. | TR | NET$ méd. | PF ×1.5 | DD% ×1.5 | NET$ ×1.5 |
|---|---|---|---|---|---|---|---|
| FULL | 1.33 | **61.81** | 471 | +9 384 | 1.27 | **71.73** | +5 891 |
| IS   | 1.12 | 61.81 | 252 | **−2 599** | 1.05 | 68.23 | −4 790 |
| OOS1 | 1.61 | 19.53 | 123 | +5 625 | 1.54 | 21.68 | +4 757 |
| OOS2 | 1.67 | 17.25 | 96  | +6 358 | 1.62 | 18.40 | +5 925 |

### 5 stratégies déployées

| Stratégie (paire) | Fenêtre | PF méd. | DD% méd. | TR | NET méd. (devise) | PF ×1.5 | NET ×1.5 |
|---|---|---|---|---|---|---|---|
| consecbar (GBP_JPY) | FULL | 0.75 | 557.93 | 3650 | −56 976 JPY | 0.68 | −77 999 JPY |
| | IS | 0.75 | 312.47 | 2028 | −32 096 JPY | 0.67 | −43 352 JPY |
| | OOS1 | 0.74 | 154.53 | 908 | −15 026 JPY | 0.66 | −20 911 JPY |
| | OOS2 | 0.79 | 111.30 | 713 | −9 762 JPY | 0.71 | −13 640 JPY |
| vwpreversion (USD_CHF) | toutes | 0.00 | 0.00 | 0 | 0 | 0.00 | 0 |
| monthweekphase (USD_JPY) | FULL | 0.85 | 108.76 | 419 | −10 043 JPY | 0.81 | −12 612 JPY |
| | IS | 0.93 | 48.13 | 248 | −2 947 JPY | 0.89 | −4 549 JPY |
| | OOS1 | 0.92 | 55.67 | 107 | −1 382 JPY | 0.88 | −2 020 JPY |
| | OOS2 | 0.63 | 49.66 | 62 | −3 462 JPY | 0.61 | −3 685 JPY |
| compmomentum (USD_JPY) | FULL | 1.02 | 97.96 | 757 | +443 JPY | 1.00 | −1 214 JPY |
| | IS | 0.90 | 97.96 | 446 | −7 162 JPY | 0.88 | −8 308 JPY |
| | OOS1 | 1.40 | 10.82 | 133 | +4 464 JPY | 1.37 | +4 197 JPY |
| | OOS2 | 1.18 | 14.29 | 173 | +2 385 JPY | 1.16 | +2 146 JPY |
| ltrsi3 (EUR_USD) | FULL | 0.76 | 381.56 | 4631 | −38 902 USD | 0.68 | −56 025 USD |
| | IS | 0.81 | 187.71 | 2701 | −17 717 USD | 0.73 | −26 329 USD |
| | OOS1 | 0.64 | 160.48 | 1195 | −15 949 USD | 0.57 | −21 166 USD |
| | OOS2 | 0.79 | 60.09 | 716 | −5 405 USD | 0.69 | −8 637 USD |

---

## (b) Verdicts — ce qui change et ce qui ne change pas

Porte : PF ≥ 1.05, DD ≤ 35 %, OOS1/OOS2 PF < 1.0 ⇒ invalide. Sharpe non exploitable (métrique par
bougie H1).

**⚠️ Le verdict change pour les 3 candidats or — tous basculent de PASSE à ÉCHEC sur le DD.**

| Stratégie | Verdict rejeu précédent (50 k$, sizing propre) | Verdict D37 (10 k$, 1 %) | Changement |
|---|---|---|---|
| gold_dualgate | PASSE (DD 8.39 %) | **ÉCHEC — DD 47.85 % / 55.13 % > 35 %** | **OUI** |
| gold_turtle_dxy | PASSE (DD 9.60 %) | **ÉCHEC — DD 49.57 % / 60.20 % > 35 %** | **OUI** |
| gold_weekday_dxy | PASSE (DD 6.25 %) | **ÉCHEC — DD 61.81 % / 71.73 % > 35 %** | **OUI** |
| consecbar | ÉCHEC (PF 0.80) | ÉCHEC (PF 0.75 / 0.68) | non |
| vwpreversion | non établi (0 trade) | non établi (0 trade) | non |
| monthweekphase | ÉCHEC (PF 0.87) | ÉCHEC (PF 0.85 / 0.81) | non |
| compmomentum | ÉCHEC (FULL 1.02 < 1.05) | ÉCHEC (FULL 1.02 / 1.00 < 1.05) | non |
| ltrsi3 | ÉCHEC (PF 0.76) | ÉCHEC (PF 0.76 / 0.68) | non |

**Lecture.** Le PF des candidats or est quasiment inchangé par le sizing (1.28→1.32/1.25/1.33) : le
profit factor est peu sensible au re-pondération 1/ATR. C'est le **DD%** qui explose, pour deux raisons
cumulées : (1) le capital passe de 50 k$ à 10 k$ (même DD en dollars ⇒ ~5× en %), (2) le sizing à 1 %
produit des positions ~2-3× plus grosses que les 10 oz fixes (leverage ~4× en moyenne, ~50× aux ATR
les plus faibles). À 10 k$ / 1 %, **les trois candidats or dépassent largement la borne DD ≤ 35 %**.
Le candidat 3 (celui retenu par Martin) a de plus un **net IS négatif** (−2 599 $ / −4 790 $) : son net
FULL est porté par OOS1+OOS2, pas par l'in-sample.

Les 5 déployées restent toutes en échec (PF < 1.05 partout) : **aucun verdict ne change**. compmomentum
rate toujours FULL de ~0.03-0.05 (1.02 / 1.00 contre 1.05). ltrsi3 est déjà au sizing D37 par
construction (`calcRiskPosition` = 1 % / 10 k$ / 2×ATR) : son résultat est identique au rejeu précédent.

---

## (c) Preuve MAE — le stop ATR(14) lie-t-il ?

Pour chaque candidat or : MAE (pire excursion adverse) par transaction, comparée à la distance de stop
ATR(14), puis effet sur le PF d'un stop ATR(14) réellement **attaché** (à taille fixe 10 oz, pour isoler
l'effet du stop de l'effet du sizing).

| Candidat | Trades | **Stop touché** | MAE moy / max | Stop moy | PF sans stop → avec stop | Net sans → avec |
|---|---|---|---|---|---|---|
| gold_dualgate | 683 | **561 (82.1 %)** | 12.30 / 88.94 | 4.06 | 1.32 → **1.00** | +12 217 $ → **−1 236 $** |
| gold_turtle_dxy | 674 | **555 (82.3 %)** | 12.42 / 88.94 | 4.12 | 1.28 → **0.96** | +10 849 $ → **−1 857 $** |
| gold_weekday_dxy | 471 | **359 (76.2 %)** | 13.46 / 172.69 | 4.85 | 1.28 → **1.07** | +4 022 $ → **+109 $** |

**Réponse sans ambiguïté : oui, le stop ATR(14) lie, et il détruit la stratégie.** Sur **~76-82 % des
transactions**, l'excursion adverse atteint ou dépasse 1×ATR(14) (MAE moyenne ≈ 3× le stop). Attacher ce
stop fait chuter le PF de 1.28-1.32 à **0.96-1.07** et ramène le net de fortement positif à ~0 ou
négatif. L'edge des Turtle or et du vendredi long est précisément de **laisser respirer** les positions
(une tendance or traverse des replis de ~12 $ avant de résoudre) : un stop protecteur serré transforme la
stratégie validée en une autre, perdante.

**Conséquence opérationnelle de premier ordre** : ces trois stratégies **n'ont pas de stop propre** et ne
peuvent pas en porter un sans être détruites. Elles sont donc **incompatibles avec la règle D30** (« le
stop part sur l'ordre, une garde refuse toute entrée sans stop ») du chemin live. Ce n'est pas un échec
du rejeu — c'est une information de premier ordre pour la décision de déploiement : le sizing D37 et
l'exigence D30 ne peuvent pas être satisfaits simultanément par ces candidats.

---

## (d) Sizing effectivement utilisé et cas limites

| Stratégie | Stop | Distance stop (moy / min / max) | Unités (min / max) | Unités brutes min | Faisable (lot min) ? |
|---|---|---|---|---|---|
| gold_dualgate | **ATR(14) inventé** | 4.21 / 0.33 / 21.29 USD | 5 / 307 oz | 4.7 oz | Oui (≥ 1 oz) |
| gold_turtle_dxy | **ATR(14) inventé** | 4.27 / 0.33 / 26.86 USD | 4 / 307 oz | 3.7 oz | Oui (≥ 1 oz) |
| gold_weekday_dxy | **ATR(14) inventé** | 4.85 / 0.41 / 22.98 USD | 4 / 246 oz | 4.4 oz | Oui (≥ 1 oz) |
| consecbar | propre (1×ATR) | 0.29 / 0.06 / 5.01 JPY | 3 200 / 269 300 | 3 155 | Oui (≥ 100 u) |
| vwpreversion | propre (1.5×ATR) | — (0 trade) | — | — | — |
| monthweekphase | propre (1.5×ATR) | 0.24 / 0.015 / 1.09 JPY | 14 400 / 1 038 000 | 14 435 | Oui (≥ 100 u) |
| compmomentum | propre (2×ATR) | 0.55 / 0.14 / 2.83 JPY | 5 600 / 114 800 | 5 590 | Oui (≥ 100 u) |
| ltrsi3 | propre (2×ATR) | 0.0027 / 0.0001 / 0.0157 USD | 6 400 / 921 100 | 6 380 | Oui (≥ 100 u) |

**Stratégies ayant eu besoin d'un stop inventé (ATR(14), nommées explicitement)** : les **3 candidats
or** (`gold_dualgate`, `gold_turtle_dxy`, `gold_weekday_dxy`). Aucune des 5 déployées n'en a eu besoin —
elles portent déjà un stop (consecbar 1×ATR, vwpreversion 1.5×ATR, monthweekphase 1.5×ATR,
compmomentum 2×ATR, ltrsi3 2×ATR).

**Contrainte de taille minimale (1 unité)** : **ne rend le sizing infaisable pour aucune stratégie.** Les
unités brutes minimales (3.7-4.7 oz or ; 3 155-14 435 u FX) restent toutes au-dessus du lot minimal
(1 oz / 100 u). Le problème est **inverse** : aux stop les plus serrés (ATR minimal), le budget de 1 %
produit des positions énormes (jusqu'à 307 oz ≈ ~50× le capital sur l'or ; jusqu'à ~1 M d'unités sur
USD_JPY), qui gonflent le DD% — c'est la cause directe des échecs de porte en (b).

---

## (e) SHA poussé

Branche `research/fixed-sizing-1pct` poussée sur `origin` (non mergée). SHA du commit final : voir
`git rev-parse HEAD` (le SHA exact est rapporté après le commit, car tout amend le changerait ; la
référence d'autorité est la branche poussée).

Fichiers : `trading-examples/.../examples/RunFixedSizingReplay.java` (nouveau runner),
`reports/2026-10-01-fixed-sizing-replay.md` (ce document). Aucune modification de `RealCostModel`,
`SwapCalculator`, ni `PerformanceMetrics`.

---

## (f) Reste non établi

1. **Sharpe de la porte** : toujours non exploitable (métrique par bougie H1, incohérente avec PF/net) —
   aucun verdict ne s'appuie dessus. La réécriture du Sharpe (autre branche) est hors périmètre.
2. **DD% des paires JPY-quotées** (consecbar, monthweekphase, compmomentum) : net en JPY mélangé à un
   capital USD ⇒ artefact d'unité ; ces stratégies échouent de toute façon sur le PF, mais leur DD% n'est
   pas un chiffre de risque lisible.
3. **vwpreversion** : toujours 0 trade (entrée conditionnée à un pic de volume, CSV bid-only sans volume)
   — PF/DD non établis.
4. **Stop inventé = 1×ATR(14)** : c'est le dénominateur demandé, mais la MAE moyenne (~12 $ ≈ 3×ATR)
   montre qu'aucun multiple d'ATR raisonnable ne rendrait le stop « non liant » pour les Turtle or. Un
   stop à 3×ATR n'a pas été testé (hors périmètre de la consigne « stop ATR(14) »).
5. **Le swap or est un instantané 2026 appliqué à l'historique** (aucune série historique) : il pèse
   lourd sur les candidats or (long gold paye ~−0.057/an). L'erreur n'est pas bornée ici (pas de balayage
   ×0/×1/×2 sur ces 8 stratégies) ; elle ne change pas le sens des verdicts DD, qui échouent déjà sans
   swap.
