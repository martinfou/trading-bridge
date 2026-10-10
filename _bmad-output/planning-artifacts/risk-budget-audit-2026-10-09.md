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
