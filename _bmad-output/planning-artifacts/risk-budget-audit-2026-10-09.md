# Audit du budget de risque — les cinq strategies de la fenetre 1

- **Date :** 2026-10-09
- **Demande :** Martin (« the risk-budget audit: one table of the five strategies, their risk %, and whether a
  walk-forward run actually establishes each edge »)
- **Sources :** `config/live-config.json` (`backtestMetrics.source`), journaux de demarrage des conteneurs
  (le % de risque lu au boot), `data/runtime/events.db` table `backtest_runs` (lecture seule),
  `data/reports/wfa/*.json` (42 rapports walk-forward).
- **Perimetre :** les 5 strategies effectivement deployeees sur la fenetre 1. Aucune modification de code ni de
  configuration n'accompagne cet audit.

## Verdict en une ligne

**Aucune des cinq strategies n'a de rapport walk-forward. Quatre des cinq n'ont, sur l'instrument qu'elles
tradent, aucun run dont le meilleur Sharpe soit positif. Le risque est donc alloue par convention, pas par
preuve.**

## La table

| Strategie | Instrument | Risque | Source declaree (config) | Run qui l'etablit | WF ? | Meilleur Sharpe sur l'instrument trade | Runs PF>1 | Live observe |
|---|---|---|---|---|---|---|---|---|
| vwpreversion | USD_CHF | **0,6 %** | « 20y H1 multi-asset backtest » | **aucun** : 38 runs sur USD_CHF, **0 trade** dans chacun | **non** | 0,00 | 0/38 | 5 entrees / 5 sorties |
| consecbar | GBP_JPY | **0,75 %** | « preliminary (needs full backtest) » | **aucun** | **non** | **-7,54** | **2/38** | 4 entrees / 3 sorties |
| compmomentum | USD_JPY | **0,4 %** | « docker-compose note; no full walk-forward report » | **aucun** | **non** | **-1,96** | 31/38 | 0 entree |
| monthweekphase | USD_JPY | **0,25 %** | « docker-compose note; no full walk-forward report » | **aucun** | **non** | **-6,74** | 7/38 | 0 entree |
| ltrsi3 | EUR_USD | **0,3 %** | « paper, Sharpe>1.0 post look-ahead fix (note) » | **aucun** | **non** | **0,50** | **0/38** | 5 entrees / 4 sorties |

Chaque « 38 runs » est la famille de runs enregistree les 2026-10-01 et 10-02 pour cette strategie, sur
l'instrument qu'elle trade : capital 10 000 $, periode 2010-01-01 a 2025-05-18 (consecbar jusqu'au
2025-12-30). La colonne Sharpe est le **maximum** de la famille : si le maximum est negatif, aucun run de la
famille n'a de Sharpe positif.

## Ce que les preuves disent

1. **Zero walk-forward sur ce qu'on trade.** Les 42 rapports de `data/reports/wfa/` concernent tous
   **LtCrossMomentum sur EUR_USD**. Le moteur existe et il produit de vrais verdicts (`wfe`, `oosSharpe`,
   `oosFold`, `oosMaxDrawdownPct`). Il n'a simplement jamais tourne sur les cinq strategies de la fenetre.
2. **Sur l'instrument trade, le meilleur Sharpe de la famille est negatif pour trois d'entre elles**
   (consecbar -7,54 ; monthweekphase -6,74 ; compmomentum -1,96). Pour ltrsi3, le meilleur Sharpe est 0,50
   mais **aucun** de ses 38 runs n'atteint un PF de 1,00 (maximum 0,97) : un PF sous 1 signifie que la
   famille entiere est perdante avant meme de parler de risque ajuste.
3. **Le dossier de vwpreversion contredit son comportement live.** Ses 38 runs sur USD_CHF enregistrent
   **zero trade**, alors que la strategie a produit 5 entrees en 8,5 jours en live et porte la totalite du
   drawdown de la fenetre (-43,78). Deux lectures possibles, toutes deux bloquantes pour la decision : soit
   les runs stockes ne sont pas representatifs du chemin live, soit le chemin live declenche sur quelque
   chose que le backtest n'a jamais vu. Dans les deux cas, **rien dans le depot n'etablit l'edge que nous
   financons a 0,6 %**.
4. **Les sources declarees sont des notes, pas des runs.** Quatre des cinq nomment un commentaire de
   `docker-compose` ou disent elles-memes « needs full backtest ». La cinquieme nomme un backtest
   (« 20y H1 multi-asset ») et aucun artefact du depot ne correspond a cette affirmation pour USD_CHF.
5. **Le risque total engage est de 2,3 % par transaction cumules** (0,6 + 0,75 + 0,4 + 0,25 + 0,3), soit
   environ 45 CAD engages par tour de signaux sur un compte a ~1 950 CAD, et **aucun de ces 2,3 points n'est
   adosse a une preuve**. Les deux strategies qui tradent reellement concentrent 1,35 point (consecbar 0,75
   et vwpreversion 0,6), et ce sont deux des trois pires dossiers de la table.

## Limites de cet audit (ce que je n'ai pas verifie)

- Je n'ai pas ouvert les parametres des 38 runs de chaque famille. J'ai agrege le **maximum** des metriques :
  c'est la lecture la plus genereuse possible, et elle reste defavorable.
- `backtest_runs` n'a **aucune colonne** in-sample / out-of-sample : le walk-forward ne peut pas se lire dans
  la base. J'ai donc utilise la presence d'un rapport `wfa-*.json` comme critere.
- Les runs a **0 trade** de vwpreversion peuvent etre un artefact de persistance (le depot porte un
  `totalTrades=0` code en dur sur le chemin streaming, `RunManager`) plutot qu'une strategie qui ne
  declenche jamais. Je le signale comme a verifier, je ne le conclus pas.
- La famille de runs est a **10 000 $ de capital** alors que la fenetre tourne a ~1 950 CAD. Par la doctrine
  de promotion du depot, un candidat valide a un sizing et deploye a un autre n'a **aucune esperance
  mesuree** : meme les runs existants ne sont pas au bon format.

## Ce que cet audit implique pour la fenetre

Cet audit est un **constat**, pas une demande de changer quoi que ce soit pendant la fenetre 1 : toucher au
sizing avant le 31 octobre detruirait la valeur de l'observation en cours. Les deux suites possibles, dans
cet ordre :

1. **Produire la preuve manquante** (aucun impact live) : un rapport walk-forward par strategie et par
   instrument deploye, au sizing de la fenetre, sur le modele de couts reel. Le moteur et le format de
   verdict existent deja.
2. **Allouer au resultat, pour la fenetre 2** : financer ce qu'un rapport etablit, au sizing valide, et
   reduire a une taille temoin ce qui ne l'est pas. C'est la decision de Martin, pas une consequence
   automatique de cet audit.

---

## Re-baseline MESUREE apres cet audit (2026-10-10)

`RunCostModelReal` (`trading-examples`), qui rejoue les cinq strategies **deployees** sur l'instrument de
leur conteneur, avec le modele de couts autoritaire `RealCostModel` (commission 0 + demi-spread mesure par
jambe + swap corrige) sur les fenetres de `docs/lt-strategy-playbook.md` (FULL 2010-2025, IS 2010-2018,
OOS1 2019-2022, OOS2 2023-2025). Porte du depot : PF >= 1.05, Sharpe >= 0.3, DD <= 35 %, et **OOS1/OOS2
PF < 1.0 = invalide**. Sortie brute : `~/.hermes/cache/scratch/cmr.out` (stdout et stderr separes, 138
lignes, `RUNNER_RC=0`).

| Strategie | Trades | PF FULL | PF IS | PF OOS1 | PF OOS2 | Sharpe FULL | Net FULL | Verdict porte |
|---|---|---|---|---|---|---|---|---|
| consecbar (GBP_JPY) | 3650 | **0,80** | 0,80 | 0,81 | **0,76** | -8,50 | -840,56 | **ECHEC sur les 4 fenetres** |
| ltrsi3 (EUR_USD) | 4631 | **0,76** | 0,81 | **0,64** | **0,79** | +0,38 | -38 901,95 | **ECHEC sur les 4 fenetres** |
| monthweekphase (USD_JPY) | 419 | **0,87** | 0,88 | 1,36 | **0,69** | -11,01 | -124,82 | **OOS2 < 1,0 donc invalide** |
| compmomentum (USD_JPY) | 757 | **1,02** | **0,84** | 1,36 | 1,30 | -3,41 | -9,23 | **FULL et IS sous 1,0** : dependant de regime, non valide |
| vwpreversion (USD_CHF) | **0** | 0,00 | 0,00 | 0,00 | 0,00 | 0,00 | 0,00 | **non jugeable sur ces donnees** |

**Aucune des cinq ne passe la porte.** Les fenetres hors echantillon ne sauvent que compmomentum, dont le
FULL et le IS sont sous 1,0 : c'est exactement le cas que la doctrine decrit comme « dependant de regime,
pas valide », a dire tel quel plutot qu'a moyenner.

### vwpreversion : la cause du zero est trouvee, et ce n'est pas la strategie

`VWPReversionStrategy` n'entre que si un pic de volume est present : `VOLUME_SPIKE_THRESHOLD = 1.3` et
`volumeRatio = avgVolume > 0 ? bar.volume() / avgVolume : 1.0` (lignes 15, 53-54, puis le test
`volumeRatio > VOLUME_SPIKE_THRESHOLD` aux lignes 73 et 80). Or `data/historical/USD_CHF_H1.csv` porte
**volume = 0 sur chaque ligne** (verifie : entete `timestamp,open,high,low,close,volume` et toutes les
valeurs a 0), comme `EUR_USD_H1.csv` et `USD_JPY_H1.csv`. Avec un volume moyen nul, le ratio vaut
**exactement 1.0**, qui n'est jamais > 1,3 : la strategie **ne peut pas entrer**, et le backtest rapporte
zero trade au lieu de « pas d'edge ». C'est le piege de donnees documente du depot
(`references/cost-model-and-gates.md`, « A volume-gated strategy cannot be judged on the repo's CSVs »).

Consequences a retenir :
- les 38 runs stockes de `vwpreversion` sur USD_CHF (0 trade) ne disent **rien** de la strategie : ils
  disent que le jeu de donnees local n'a pas de volume. Le dossier n'est pas mauvais, il est **vide**.
- `GBP_JPY_H1.csv`, lui, porte un **vrai volume** (15849, 10953, ...) et `ConsecutiveBarExhaustionStrategy`
  n'utilise pas le volume : les resultats de consecbar ci-dessus sont donc exploitables.
- pour juger vwpreversion il faut **recuperer des bougies H1 AVEC volume** (OANDA `price=M`, qui porte un
  champ volume) avant de relancer le harnais. Tant que ce n'est pas fait, ses 0,6 % de risque ne reposent
  sur rien de mesurable dans ce depot.

### Ce que la re-baseline dit du modele de couts, pas seulement des strategies

1. **Le modele reel degrade chaque strategie qui trade.** PF FULL passage : consecbar 0,97 -> 0,80,
   monthweekphase 0,93 -> 0,87, ltrsi3 0,84 -> 0,76, compmomentum 1,05 -> 1,02. Les chiffres stockes dans
   `live-config.json` **ne reproduisent pas** ; c'est la prediction de la doctrine (« re-run the gate rather
   than quoting the stored number ») et c'est maintenant mesure.
2. **Le spread domine, exactement comme la calibration l'annoncait** : 91 % du cout pour consecbar, 100 %
   pour ltrsi3, 90 % pour monthweekphase, 60 % pour compmomentum. Pour consecbar et ltrsi3, le cout total
   vaut **752 %** du P&L brut de prix : la strategie paie 7,5 fois son edge brut en couts.
3. **Frequence contre seuil de survie** (regle du depot : 200+ trades/an exige PF > 1,30) : ltrsi3 trade
   4631 fois sur 15 ans (~309/an) avec PF 0,76 ; consecbar 243/an avec PF 0,80 ; compmomentum 50/an avec
   PF 1,02 la ou il faudrait > 1,15 ; monthweekphase 28/an tombe dans la zone ou les couts sont mineurs, et
   il perd quand meme (PF 0,87), donc son probleme est le signal.
4. **Le verdict ne bouge pas sous l'incertitude du swap** : le balayage x0 / x1 / x2 laisse les PF
   identiques a l'affichage (0,80 / 0,87 / 1,02 / 0,76). L'erreur du snapshot de taux est donc **bornee** et
   n'explique pas les echecs.

### Ce que cela change pour la decision

- Le constat n'est plus « le risque n'est pas adosse a une preuve », c'est **« aucune des cinq strategies
  deployees ne passe la porte du depot sur l'instrument qu'elle trade »**. La porte etait dans le depot
  depuis le debut ; elle n'avait jamais ete rouverte sur le set deploye.
- Les 2,3 % de risque par transaction sont donc repartis sur cinq candidats qui echouent, dont 1,35 point
  sur les deux plus mauvais dossiers (consecbar, vwpreversion) et 0,9 point sur ltrsi3 qui perd 38 902 CAD
  en 15 ans au sizing fixe.
- Recommandation, sans toucher a la fenetre en cours : considerer la fenetre 2 comme un **redemarrage de
  l'allocation**, en n'y finançant qu'un candidat qui passe la porte au sizing de la fenetre, et en
  remettant a une taille temoin ce qui ne la passe pas. Avant cela, deux prealables mesurables :
  (a) recuperer des donnees H1 avec volume pour juger vwpreversion, (b) decider si les quatre autres sont
  abandonnees ou repassees au crible (`--wfa`) avec une recherche de parametres, en sachant que le cout de
  spread, lui, ne baissera pas.
