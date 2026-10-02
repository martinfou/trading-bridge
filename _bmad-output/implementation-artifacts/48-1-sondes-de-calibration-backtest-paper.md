# Story 48.1 — Sondes de calibration backtest contre paper

- **Status : draft** (propose-only : le périmètre attend un mot de Martin, voir « Question ouverte »)
- **Date :** 2026-10-02
- **Propriétaire :** Martin
- **baseline_commit :** 4a6e8bb7
- **Origine :** demande directe de Martin (2026-10-02) : « des stratégies qui n'ont pas besoin d'être
  profitables mais qui vont être utiles pour comparer backtesting et paper trading ».

## Pourquoi maintenant

La fenêtre 1 a montré qu'un écart backtest/paper peut coûter de l'argent sans que personne ne sache d'où il
vient : le cooldown non armé après une sortie venue du courtier (story 47.1) a produit une réentrée de 3
minutes après un stop, invisible en backtest. Le dépôt n'a aujourd'hui aucun moyen de **mesurer** ce genre
d'écart : il a des stratégies qui cherchent à être rentables, donc dont l'écart backtest/paper est
inextricable de leur logique.

Une sonde de calibration renverse la contrainte : elle ne cherche pas la rentabilité, elle cherche à être
**prévisible**. Si son résultat en backtest est calculable à la main et que le paper s'en écarte, l'écart est
imputable à l'exécution, et à rien d'autre.

## Périmètre (3 sondes + 1 comparateur)

### S1 — `ProbeTimeEntry` : la chaîne nominale

- Entrée long à une heure UTC fixe, un jour de semaine fixe, taille **fixe en unités**, aucun indicateur,
  aucun signal.
- Sortie après **N barres** exactement, sans stop ni target.
- But : comparer prix de fill, horodatage, spread appliqué, swap, PnL. Le backtest doit être reproductible à
  la main depuis la série de barres ; le paper doit coller à la barre près.
- Ce qu'une divergence révèle : mauvais bar mapping, spread du modèle faux, décalage d'heure, taille
  d'ordre modifiée en route.

### S2 — `ProbeStopTouch` : l'exécution qui passe par le courtier

- Entrée long à heure fixe, stop à **X pips qui sera touché** et target à Y pips, taille fixe.
- Deux variantes : stop touché dans la barre d'entrée, stop touché 3 barres plus tard.
- But : comparer l'exécution d'un stop côté moteur (`FillMode.TRADE_THROUGH` en backtest) contre le stop
  attaché côté courtier (paper), et vérifier le **reset d'état** après une sortie venue du courtier.
- Ce qu'une divergence révèle : prix de sortie différent, sortie manquée, cooldown non armé, position
  fantôme après la sortie. C'est la classe exacte du bug 47.1.

### S3 — `ProbeSwapHold` : la comptabilité du portage

- Entrée long, **aucune sortie** pendant 5 jours ouvrés, en traversant un **mercredi** (triple swap OANDA).
- But : comparer le swap porté, backtest contre relevé du courtier.
- Ce qu'une divergence révèle : table de swap legacy, conversion de pip value, signe inversé.

### C1 — Le comparateur (le vrai livrable)

Sans lui, on a trois stratégies de plus, pas une comparaison. Un rapport qui, pour chaque sonde :

- aligne les trades backtest et paper **un par un** (horodatage, sens, prix, unités, PnL, swap) ;
- imprime l'écart par trade (prix en pips, unités, PnL en devise) et un verdict par trade :
  `IDENTIQUE` / `ÉCART TOLÉRÉ (raison)` / `DIVERGENCE` ;
- termine par un résumé : nombre de trades appariés, non appariés de chaque côté, écart cumulé.

Format de sortie : texte + un artefact JSON réutilisable, pour que le résultat soit citable dans le journal
de fenêtre et pas seulement lu dans un terminal.

## Hors périmètre

- Aucune recherche de rentabilité, aucun réglage de paramètres, aucune promotion en stratégie déployée.
- Aucun changement au moteur de backtest, au modèle de coûts ou au runner live. Si une sonde révèle un
  défaut, il devient sa propre story (comme 47.1).
- Pas de sonde sur les instruments non FX (futures/actions) dans cette itération.

## Critères d'acceptation

1. Les trois sondes existent, sont enregistrées dans le catalogue de stratégies, et **refusent** d'être
   déployées en live (garde explicite : une sonde ne doit jamais trader un compte réel).
2. Chaque sonde produit, en backtest, un résultat **calculable à la main** : la documentation de la story
   contient le calcul attendu pour la fenêtre de référence, et le test l'assère.
3. Chaque sonde a un test unitaire qui prouve sa prévisibilité (mêmes entrées, mêmes sorties, aucune
   dépendance à l'horloge système).
4. Le comparateur apparie les trades par (horodatage, sens) avec une tolérance documentée, et distingue
   explicitement « trade non apparié » de « trade apparié avec écart ».
5. Le comparateur tourne sur données réelles : une sonde exécutée en backtest sur la fenêtre de référence et
   en paper sur le compte `-014`, avec un rapport produit et archivé.
6. Le comparateur ne nécessite aucune lecture d'état interne du runner : il lit les trades du côté backtest
   et les transactions du côté courtier, sinon il hérite des angles morts du runner.
7. La sonde S2 reproduit au moins une fois le scénario « sortie venue du courtier » et vérifie que le
   cooldown est armé (non-régression croisée avec 47.1).

## Risques et inconnues à lever en phase dev

- **Taille fixe en unités** : les stratégies du dépôt dimensionnent par risque. Il faut un chemin de sizing
  fixe pour les sondes, sans toucher au sizing des stratégies déployées. À vérifier : `LotSizing` et la
  configuration par stratégie.
- **Fenêtre de référence** : quelle période et quel symbole choisir pour que le calcul à la main reste
  faisable (une paire liquide, quelques semaines, pas un an).
- **Réentrée après sortie courtier** : le runner réconcilie l'état ; vérifier comment une sonde voit sa
  position réelle, et documenter ce que le comparateur doit faire d'un trade paper que le backtest ne
  connaît pas.
- **Swap mercredi** : confirmer sur relevé réel quel jour porte le triple swap pour la paire choisie, sans
  quoi S3 ne prouve rien.

## Question ouverte (un mot suffit)

Périmètre de la spec, à confirmer :
`3 sondes + comparateur` (recommandé, cette version) / `1 seule sonde minimale d'abord` /
`priorité exécution courtier` / `priorité comptabilité`.

## Flux

1. Martin tranche le périmètre (une ligne).
2. Phase dev par lots courts : une sonde, son test calculé à la main, puis la suivante.
3. Revue indépendante avant tout déploiement, comme pour 47.1.
4. Aucun déploiement de sonde : elles servent à mesurer, pas à trader.
