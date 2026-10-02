# Shortlist — rejeu des 8 candidats sous le vrai modèle de coûts (RealCostModel)

Date : 2026-10-01 — branche `research/shortlist-real-costs` — worktree dédié.

## Contexte

`RealCostModel` (commission 0, demi-spread mesuré par jambe, swap = fraction annuelle) est
mergé dans master et validé. Les 5 stratégies déployées ont été rejouées : aucune ne survit.
Ce document rejoue les **8 candidats** du shortlist aux **deux niveaux de spread** demandés :
la **médiane mesurée** (`RealCostModel.HALF_SPREAD_PRICE`) et un niveau **conservateur ×1,5**
(une passe de revue a fait valoir que la médiane sous-estime la friction de la moitié des fills
et autour du rollover).

**Porte appliquée** (`docs/lt-strategy-playbook.md` §4.2 / §4.3) :
- Fenêtres : FULL 2010-2025 · IS 2010-2018 · OOS1 2019-2022 · OOS2 2023-2025.
- Seuils : **PF ≥ 1.05**, **DD ≤ 35 %**, **OOS1 ou OOS2 PF < 1.0 ⇒ invalide**.
- Sharpe : **non exploitable** (métrique par bougie H1 annualisée √6240) — verdict sur PF, DD,
  nombre de trades et net après tous coûts (spread + swap). Capital de référence 50 000 $, PnL en USD.

> ⚠️ **Fenêtres : divergence assumée avec la recherche d'origine.** Les candidats or ont été
> explorés sur 2006-2025 (WF IS 2006-2015 / OOS 2016-2025), d'où leurs chiffres publiés
> (ex. OR gate 839 trades). La porte §4.2 impose FULL **2010**-2025 : les 4 années 2006-2009
> sont retirées, donc les compteurs de trades et les PF diffèrent des valeurs publiées. C'est
> voulu : on rejuge sur la porte, pas sur la plage de calibration d'origine.

---

## (a) Tableau complet par candidat et par fenêtre

`PF` = profit factor (voit le spread, pas le swap) · `DD%` · `TR` = trades · `NET$` = net après
**tous** les coûts (spread + swap) · `SWAP$` = composante swap.

### Candidat 1 — Gold Turtle 55/20 + double porte OR (XAU_USD)

| Fenêtre | PF méd. | DD% méd. | TR | NET$ méd. | PF ×1.5 | DD% ×1.5 | NET$ ×1.5 |
|---|---|---|---|---|---|---|---|
| FULL | 1.32 | 8.39 | 683 | +12 217 | 1.27 | 9.26 | +10 407 |
| IS   | 1.07 | 6.63 | 387 | −17 | 1.02 | 7.50 | −1 041 |
| OOS1 | 1.11 | 6.86 | 169 | +646 | 1.08 | 7.03 | +198 |
| OOS2 | 1.96 | 5.16 | 127 | +11 595 | 1.93 | 5.27 | +11 258 |

### Candidat 2 — Gold Turtle + DXY opposé (XAU_USD)

| Fenêtre | PF méd. | DD% méd. | TR | NET$ méd. | PF ×1.5 | DD% ×1.5 | NET$ ×1.5 |
|---|---|---|---|---|---|---|---|
| FULL | 1.28 | 9.60 | 674 | +10 849 | 1.23 | 10.16 | +9 063 |
| IS   | 1.06 | 6.01 | 381 | +219 | 1.01 | 6.68 | −791 |
| OOS1 | 1.20 | 6.40 | 170 | +1 841 | 1.16 | 6.61 | +1 391 |
| OOS2 | 1.71 | 5.21 | 124 | +8 769 | 1.68 | 5.33 | +8 440 |

### Candidat 3 — Gold vendredi long × DXY opposé (XAU_USD)

| Fenêtre | PF méd. | DD% méd. | TR | NET$ méd. | PF ×1.5 | DD% ×1.5 | NET$ ×1.5 |
|---|---|---|---|---|---|---|---|
| FULL | 1.28 | 6.25 | 471 | +4 022 | 1.23 | 6.47 | +2 774 |
| IS   | 1.23 | 5.58 | 252 | +756 | 1.17 | 6.43 | +88 |
| OOS1 | 1.40 | 3.18 | 123 | +1 398 | 1.34 | 3.34 | +1 072 |
| OOS2 | 1.28 | 6.50 | 96  | +1 869 | 1.25 | 6.55 | +1 614 |

### Candidat 4 — FX vendredi fade GBP_JPY + overlay de volatilité (GBP_JPY)

| Fenêtre | PF méd. | DD% méd. | TR | NET$ méd. | PF ×1.5 | DD% ×1.5 | NET$ ×1.5 |
|---|---|---|---|---|---|---|---|
| FULL | 1.22 | 5.18 | 832 | +1 194 | 1.17 | 5.49 | +114 |
| IS   | 1.30 | 4.86 | 468 | +1 678 | 1.25 | 5.38 | +1 101 |
| OOS1 | 1.51 | 2.11 | 208 | +1 719 | 1.44 | 2.41 | +1 400 |
| OOS2 | **0.71** | 5.15 | 156 | **−2 261** | **0.69** | 5.33 | **−2 444** |

### Candidat 6 — Gold vendredi simple (XAU_USD)

| Fenêtre | PF méd. | DD% méd. | TR | NET$ méd. | PF ×1.5 | DD% ×1.5 | NET$ ×1.5 |
|---|---|---|---|---|---|---|---|
| FULL | 1.17 | 10.26 | 832 | +2 156 | 1.13 | 13.45 | −49 |
| IS   | 1.34 | 5.68 | 468 | +3 336 | 1.27 | 7.06 | +2 097 |
| OOS1 | 1.09 | 4.67 | 208 | −484 | 1.05 | 5.29 | −1 034 |
| OOS2 | 1.03 | 7.04 | 156 | −694 | **1.01** | 7.35 | −1 107 |

### Candidat 7 — Gold janvier (XAU_USD)

| Fenêtre | PF méd. | DD% méd. | TR | NET$ méd. | PF ×1.5 | DD% ×1.5 | NET$ ×1.5 |
|---|---|---|---|---|---|---|---|
| FULL | 4.06 | 3.88 | **16** | +4 705 | 4.01 | 3.89 | +4 663 |
| IS   | 4.34 | 3.88 | **9**  | +2 663 | 4.30 | 3.89 | +2 639 |
| OOS1 | 1.37 | 3.14 | **4**  | −122 | 1.36 | 3.15 | −133 |
| OOS2 | 11.99 | 1.74 | **3**  | +2 165 | 11.83 | 1.74 | +2 157 |

---

## (b) Verdict par candidat

**Rappel de la définition :** « fragile » = passe à la médiane mais échoue au conservateur.
Aucun candidat ne tombe *exactement* dans ce cas — le ×1,5 ne renverse aucun verdict, car le
demi-spread or (0,265 $ sur un prix ~1 600-4 400 $) est minuscule en relatif et ces stratégies
sont peu fréquentes. En revanche deux candidats sont **fragiles au sens large** (échantillon
trop petit / érosion au seuil) et sont étiquetés explicitement.

1. **C1 — Gold Turtle 55/20 + double porte OR → PASSE aux deux niveaux.** FULL 1.32/1.27 ≥ 1.05,
   OOS1 1.11/1.08 et OOS2 1.96/1.93 ≥ 1.0, DD ≤ 9.26 %. Nuance : l'IS (2010-2018) est à
   1.07/1.02 avec un net IS ≈ 0 à −1 041 $ — le net FULL est porté quasi entièrement par OOS2
   (2023-2025, +11 258 $). Pas fragile, mais concentré sur la fin de période.

2. **C2 — Gold Turtle + DXY opposé → PASSE aux deux niveaux.** FULL 1.28/1.23, OOS 1.20/1.16 et
   1.71/1.68, DD ≤ 10.16 %. Même nuance IS (1.06/1.01, net IS −791 $ au conservateur).

3. **C3 — Gold vendredi long × DXY opposé → PASSE aux deux niveaux, le plus propre du lot.**
   Toutes fenêtres ≥ 1.17 même au ×1,5, net positif partout, DD ≤ 6.55 %. C'est le seul candidat
   or dont le PF tient dans chaque fenêtre sans réserve.

4. **C4 — FX vendredi fade GBP_JPY + overlay vol → NE PASSE PAS (invalide).** OOS2 = 0.71/0.69
   < 1.0 ⇒ invalide selon la règle de la porte, malgré un bon IS/OOS1 (1.30-1.51). Net OOS2
   fortement négatif (−2 261 $). L'edge est concentré 2010-2022 et se retourne en 2023-2025
   (régime-dépendant).

5. **C5 — GBP_USD fade → NON EXÉCUTABLE.** GBP_USD n'est pas dans les paires mesurées de
   `RealCostModel.HALF_SPREAD_PRICE` ; le modèle **refuse** (`IllegalArgumentException`). On ne
   contourne pas, on le dit.

6. **C6 — Gold vendredi simple → FRAGILE.** Passe formellement (FULL 1.17/1.13 ≥ 1.05, aucun
   OOS < 1.0) mais **OOS2 = 1.03 / 1.01** est au bord exact de l'invalidation, le net OOS est
   négatif aux deux niveaux (−484/−694 méd. ; −1 034/−1 107 ×1.5) et le net FULL s'effondre de
   +2 156 $ à **−49 $** au conservateur. C'est l'érosion documentée (IS 1.56 → OOS 1.10) qui se
   confirme : l'edge vendredi est redevenu ≈ la bêta or, le swap (hold de week-end) ronge tout.
   À rejeter comme candidat à la promotion.

7. **C7 — Gold janvier → FRAGILE / non concluant.** PF spectaculaire (4.06/4.01) mais sur
   **16 trades** FULL (9 IS / 4 OOS1 / **3 OOS2**). Un PF de 11.99 sur 3 trades n'est pas une
   preuve ; OOS1 (4 trades) est même net négatif. La porte passe en PF/DD, mais le verdict
   repose sur un échantillon ~20× sous le seuil de ~100 trades — **non concluant, pas validé.**

8. **C8 — USD_CAD saisonnalité → NON EXÉCUTABLE.** USD_CAD non mesuré → `RealCostModel` refuse.
   (Leçon déjà établie par ailleurs : avec le vrai swap, IS +226 $ / OOS −448 $ ; fenêtre-fitting
   suspecté.)

---

## (c) Mécanique de l'edge par candidat (une ligne chacun)

| # | Candidat | Mécanique | Instrument mesuré ? |
|---|---|---|---|
| 1 | Gold Turtle 55/20 double porte OR | Suivi de tendance (Donchian 55/20) sur l'or, entrée filtrée par l'**union** de deux régimes refuge (DXY « USD ferme » OU S&P « stress actions ») | XAU_USD ✓ |
| 2 | Gold Turtle + DXY opposé | Suivi de tendance Donchian sur l'or, entrée filtrée par un seul régime (DXY synthétique vs SMA 500 H1, polarité opposée) | XAU_USD ✓ |
| 3 | Gold vendredi long × DXY opposé | Effet **calendaire** (bid safe-haven du vendredi) long or, conditionné au régime DXY opposé | XAU_USD ✓ |
| 4 | FX vendredi fade GBP_JPY + overlay vol | **Retour à la moyenne / portage** — SELL vendredi (prime de risque de week-end), taille doublée si vol élevée | GBP_JPY ✓ |
| 5 | GBP_USD fade | Même structure calendaire vendredi que le n°4, sur GBP_USD | GBP_USD ✗ (non mesuré) |
| 6 | Gold vendredi simple | Effet calendaire vendredi long or, non conditionné | XAU_USD ✓ |
| 7 | Gold janvier | **Saisonnalité calendaire** (fenêtre Jan 1-31 BUY) | XAU_USD ✓ |
| 8 | USD_CAD saisonnalité | **Saisonnalité calendaire** (fenêtre Oct 12-Nov 26 BUY) | USD_CAD ✗ (non mesuré) |

---

## (d) Candidats non exécutables et pourquoi

- **C5 (GBP_USD fade)** et **C8 (USD_CAD saisonnalité)** : leurs instruments ne sont **pas** dans
  les six paires dont le demi-spread est mesuré (EUR_USD, GBP_JPY, USD_JPY, AUD_USD, USD_CHF,
  XAU_USD). `RealCostModel.halfSpread()` **lève une exception** et nomme la paire au lieu de
  replier sur une valeur qui sous-estimerait le coût. C'est le comportement voulu (« repli =
  refus », revue 2). **Aucun chiffre n'est produit pour ces deux candidats** — les rejouer
  exige de mesurer 500 bougies H1 `price=BA` pour GBP_USD et USD_CAD et de les ajouter à
  `HALF_SPREAD_PRICE` (hors périmètre, d'autres branches travaillent le modèle).

---

## (e) SHA poussé

Branche `research/shortlist-real-costs` poussée sur `origin` (`git push -u origin
research/shortlist-real-costs`), non mergée. SHA du commit (à lire avec `git rev-parse HEAD`,
car il varie à chaque amend) : **`91550773759e286585b6fbeaf1d14f351633536e`** (commit final du
rapport — tout amend ultérieur le changerait ; la référence d'autorité est la branche poussée).

---

## (f) Non établi

1. **C5 et C8** : non exécutables (spread non mesuré) — verdict « non établi », pas « rejeté ».
2. **Sharpe de la porte** : toujours non exploitable (métrique par bougie H1), aucun verdict ne
   s'appuie dessus.
3. **Échantillon or 2006-2009** : retiré par la fenêtre FULL 2010-2025 de la porte ; le verdict
   des candidats or ne couvre pas la grande tendance haussière 2006-2011. Un rejeu complémentaire
   sur 2006-2025 (WF IS 2006-2015 / OOS 2016-2025) donnerait des PF plus proches des valeurs
   publiées, mais ne change pas le sens des verdicts 4/6/7.
4. **Swap = instantané 2026 appliqué à l'historique** : borné (le swap or domine C6 et C7, qui
   sont justement les deux candidats fragiles) — la série historique de financement n'existe pas
   dans le dépôt.
5. **Net négatif sur l'IS** (C1, C2) : la porte ne l'exige pas, mais c'est un signal que le net
   FULL est porté par OOS2 — à surveiller avant toute promotion.

---

## Fichiers

- `trading-examples/.../examples/RunShortlistRealCosts.java` (nouveau runner : validation
  ancien coût + porte médiane/×1.5).
- `reports/2026-10-01-shortlist-real-costs.md` (ce document).

Aucune modification de `RealCostModel`, `SwapCalculator` ni `PerformanceMetrics`.
