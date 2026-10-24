# 66e : revue de la semaine 19-23 oct 2026 (61e → 65e) + spec du modèle de coût horaire

> Simons (recherche), samedi 24 octobre 2026. Harnais et chiffres rejoués dans ce run.
> Rapport brut de la mesure du jour : `reports/2026-10-24-spread-by-hour.txt`.

## 1. La semaine en une ligne

La famille **fade du vendredi** n'est pas morte par absence d'edge : elle est morte parce que l'edge
mesuré depuis le 37e était **l'ouverture du spread**, et il a fallu acheter la donnée ASK pour le voir.
Le livrable de la semaine n'est donc pas une stratégie, c'est une **correction du modèle de coût** :
le spread a une structure horaire que `RealCostModel` ignore (spec écrite aujourd'hui, code lundi).

| Run | Sujet | Verdict |
|---|---|---|
| 61e lun 19 | le fade pré-congé existe, mais il faut que le **FX** soit fermé (pas seulement les actions US) | effet réel, 8 à 10x sous le coût |
| 62e mar 20 | le creux de 17:00 ET est le **rollover universel**, pas un settlement de l'or | reproduit 8 séries / 2 fournisseurs |
| 63e mer 21 | le fade n'a **aucune saisonnalité** ; la « saison » du drift est un **alias d'heure d'été** | 0/12 mois au Bonferroni |
| 64e jeu 22 | 86 % du fade est **l'ouverture du spread** (mesure ASK) ; la fenêtre codée a une heure d'avance | **REJECT** |
| 65e ven 23 | la branche survivante du 61e meurt aussi sur le MID ; livrable : **structure horaire du spread** | **REJECT de la famille** |

Décision de Martin le 22 oct : la lane `FridayFadeAudChf` prévue pour le 23 **n'est pas déployée**.

## 2. Les cinq méta-patterns

**a) La base de mesure était l'hypothèse cachée.** Six semaines de recherche (37e → 63e) ont mesuré un fade
sur des barres **BID**. Le MID du même instant vaut −0,02 / −0,22 / +0,55 bp (t −0,1 / −1,2 / +1,2) là où le
BID vaut +3,23 / +1,84 / +5,81 (t 10,6 / 9,3 / 12,0). Tout le reste suivait : chaque modèle de coût un peu
honnête tuait la stratégie, et personne ne comprenait pourquoi. **Un edge mesuré sur le bid n'existe pas
avant que le mid du même instant ne soit mesuré.**

**b) Le coût n'est pas une constante, c'est une fonction.** 4e occurrence de « le modèle de coût fabrique le
verdict » : la table de swap 2024-26 appliquée à 20 ans (47e), le spread du cross (55e), le compteur de jours
de rollover (56e), et maintenant **l'heure du fill**. La mesure du jour : le spread au close de barre va de
**0,95x** la médiane du symbole en `[00,16)` ET à **5,21x** en `[17,18)` ET (USD_CHF), soit un facteur 13
entre l'heure la moins chère et la plus chère.

**c) La robustesse ne valide pas le mécanisme.** Le net du fade était négatif **5/5 ères, 3/3 croix, IS et
OOS** — une stabilité parfaite. Elle disait seulement que l'artefact était stable. « Un résultat robuste peut
être robustement faux » (64e, leçon 4) est la formulation la plus utile de la semaine.

**d) Recherche et production se sont rencontrées, acte 2.** La lane devait se déployer le 23. La mesure du
22 l'a tuée la veille. C'est la deuxième fois en deux semaines (58e : `MARKET_HALTED` à l'instant exact où
l'edge se réalise). La recherche n'est pas un ornement : elle a évité une perte réelle.

**e) L'ère courante a tué ce que l'échantillon long cachait.** Le fade BID `13:00 → clôture` sur GBP_CHF vaut
+10,91 bp en 2014-17, +8,85 en 2018-21, **+1,62 en 2022-26**. Même à supposer le mécanisme vrai, il n'y avait
plus d'edge à déployer aujourd'hui. Le late-bloomer a un jumeau : le **late-fader**.

## 3. Ce que la mesure du jour ajoute (au-delà du 65e)

Le 65e concluait « `RealCostModel` sous-coste 2,5-3,4x toute jambe dans `[16,19) ET` ». Mesure élargie à
**7 symboles, 2021-2026, 34 000 barres chacun** (le 65e n'avait que 3 crosses CHF sur 2005-2026) :

- **Le NIVEAU des constantes est bon.** Médiane mesurée / constante : EUR_USD 0,97x, GBP_JPY 1,02x,
  USD_JPY 1,16x, AUD_USD 1,04x, USD_CHF 0,99x. Le modèle du 1er octobre 2026 est confirmé à ±16 % sur
  5 ans et demi. Il n'y a **pas** de problème de niveau, et donc **rien à corriger dans les constantes**.
- **La structure, elle, manque.** `[17,18)` ET : EUR 2,09x, GBP_JPY 4,33x, USD_JPY 2,80x, AUD 2,14x,
  USD_CHF 5,21x, GBP_USD 3,14x. Partout ailleurs (00 à 16 ET) : **0,95 à 1,06x**.
- **La dernière barre du vendredi est une population de JOUR, pas d'heure** : 2,55 / 8,49 / 4,64 / 8,62 /
  5,69 / 6,76 / 3,54 bp contre 1,60 / 2,91 / 1,67 / 2,50 / 2,57 / 1,90 / 2,17 la même heure lundi-jeudi.
  Le modèle doit lire `(symbole, jour, heure)`.
- **L'or n'a aucune barre en `[17,18)` ET** (n = 0 sur 5 ans) : son halt est plein. Ne pas lui inventer un
  multiplicateur, et sa constante est de toute façon **sous-costée de 24 %** (1,27 vs 1,58 bp mesurés).
- **GBP_USD est absent de `HALF_SPREAD_PRICE`** alors qu'il est mesuré ici (demi = 0,71 bp/legue) et utilisé
  par les harnais. À ajouter.
- Stabilité IS 2021-23 / OOS 2024-26 : `[17,18)` stable partout. Une seule instabilité : **GBP_USD `[16,17)`
  2,61 → 1,30**, à marquer `UNSTABLE` dans la table.

## 4. État de la production vérifié aujourd'hui (samedi 24 oct, 07:05 EDT)

| Point | État |
|---|---|
| Conteneurs | **4** `healthy` (trading-live, month-week, lt-rsi3, comp-momentum), tous `OANDA_ENV=practice` |
| Barres traitées 24 h | 20 (trading-live), 10 (chacun des 3 autres) |
| 401 | **0** sur les 4 conteneurs sur 24 h (contre 178 sur 7 jours au relevé du 17) |
| `Failed to save state` | **0** sur les 4 (contre 2 582 en 24 h le 17) |
| Lanes news | **retirées** (merge `72cfbeba`, D67/D68) |
| Images | trading-live **2026-10-22 14:43 UTC** ; les 3 autres **2026-10-09 10:07 UTC** (13 jours) |

Le symptôme du 17 a disparu non pas parce qu'un correctif a été mergé, mais parce que **l'image a été
reconstruite le 22**. C'est exactement la conclusion du 17, confirmée par le résultat.

## 5. Trouvaille : un correctif dans master que la production ne porte pas (3e « corrigé ≠ livré »)

Deux défauts dans le chemin d'ordre, mesurés dans les journaux des conteneurs (numéros de ligne du
code que porte l'image en service, pas de master) :

1. **`OandaExecutor` l.109** : `orderFill.get("tradeOpened").get("tradeID")` lève une NPE quand OANDA omet
   `tradeOpened` (un fill qui réduit une position existante). Message : `Cannot invoke JsonNode.get(String)
   ... is null`. **6 échecs sur 18 signaux** dans lt-rsi3 depuis le 9 oct (12 entrées exécutées, 6 échecs)
   et 2 dans trading-live. L'ordre est **rempli chez le courtier** avant l'exception : la position n'entre
   pas dans `activeTrades`, la sortie locale la refuse ensuite (« no tracked position »), et c'est la
   réconciliation asynchrone qui rattrape (observé le 22 oct 23:00:53, trade 478).
2. **`LiveStrategyRunner` l.1323** : `Double.parseDouble(result.fillPrice())` sur `"N/A"` quand l'ordre
   n'a pas été rempli (halt, FOK sans liquidité) → `For input string: "N/A"`. Observé le 23 oct 21:00:57.

**Les deux sont déjà corrigés dans master** par `93a5284f` (22 oct **18:53 EDT**) : garde sur `tradeOpened`,
et fill sans prix devenu une erreur explicite avec le tradeId. Mais **l'image de trading-live a été créée le
22 oct à 14:43 UTC**, soit **8 heures avant le commit** : les deux échecs du 22 oct 23:00 et du 23 oct 21:00
sont donc exactement ce que le code non déployé prédit. Les 3 autres conteneurs tournent une image du **9 oct**.

Action proposée (pas faite, elle demande ton go) : reconstruire les 4 images depuis master. Aucune ligne de
code à écrire.

## 6. Le livrable de la semaine : la spec du modèle de coût horaire

Écrite et poussée sur `docs/real-cost-model-hourly-spec` (trading-bridge, commit `d3421e2e`). Elle contient :

- la table des multiplicateurs par symbole et par tranche ET, avec la stabilité IS/OOS ;
- l'API (`SpreadSchedule`, `halfSpread(sym, instant)`), les 4 règles non négociables (pas de repli
  silencieux, heure lue en `America/New_York`, la constante reste la référence de niveau, week-end hors
  modèle) ;
- le point d'insertion réel : `BacktestEngine.applySlippage` **n'a pas d'instant de fill**, et `slippagePct`
  (SL) part d'un prix de référence figé au 1er octobre ;
- l'**effet attendu sur les 5 stratégies déployées**, avec les remplissages observés dans la fenêtre paper :
  - `ltrsi3` (EUR_USD, 100 % du coût = spread) : entrées à 20:00 / 03:00 / 08:00 ET → multiplicateur
    1,00 / 1,00 / 0,99. **Pronostic : |ΔPF| < 0,03.** S'il bouge de 10 %, le câblage est faux.
  - `vwpreversion` (USD_CHF) : entrée à 10:00 ET → 1,02. Idem.
  - `consecbar` (GBP_JPY, 93 % du coût = spread) : une entrée sur deux est tombée à **17:00 ET** →
    **4,33x**. Son PF 0,80 ne peut que baisser.
  - `monthweekphase`, `compmomentum` (USD_JPY) : aucun fill, à mesurer lundi.
  - **Aucune des 5 ne peut passer de FAIL à PASS** : leurs déficits sont de 20 à 35 points de PF, et la
    correction déplace le coût dans le mauvais sens. Le modèle horaire est une correction de **justesse**,
    pas un sauvetage. Si un PASS apparaît, il vient d'un bug.

## 7. Ce qu'il ne faut PAS re-tester

- le fade sous **aucune** ancre (64e/65e) : deux bases de mesure et trois horizons sont épuisés ;
- la saisonnalité du fade (63e) et les mesures mois x heure ancrées en UTC (63e) ;
- les modulateurs de vol (58e/59e/45e), la matrice mensuelle sur paires (48e/51e), le cross-section (49e),
  l'intraday FX (40e), la fin de mois FX (41e) ;
- **et désormais** : toute mesure de dérive ou de fade sur **barres bid seules**. Si la donnée ASK n'est pas
  disponible pour un instrument, la mesure ne vaut pas un verdict.

## 8. Prochaine semaine

- **Lundi 26** : coder le modèle horaire depuis la spec (décision du 23 oct), re-coter les 5 déployées sous
  le nouveau coût avec la colonne `costMultiplierMoyen`, et publier l'histogramme des heures de fill.
- **Lundi 26** : reconstruire les 4 images (correctifs d'ordres de `93a5284f`).
- **Semaine du 26** : reprise des News Playbooks (BoC 28, FOMC 28, ECB 29, PIB T3 + PCE 29, BoJ 30), la
  suspension courant jusqu'au 26.
- Piste neuve suivante : le **spread comme variable d'exécution** (entrée au mid par ordre limite quand le
  carnet le permet) plutôt que comme coût subi. C'est la seule porte que le 64e a laissée ouverte
  (« une entrée au mid, pas de spread payé »).

## 9. Artefacts

- `scripts/spread-by-hour.py` (nouveau, 20 s, sans Maven) + `scripts/hourly-spread-fetch.py`
- `reports/2026-10-24-spread-by-hour.txt` (rapport brut, 7 symboles, P0 + stabilité IS/OOS)
- `data/oanda-ba/{EUR_USD,GBP_USD,USD_JPY,USD_CHF,AUD_USD,GBP_JPY,XAU_USD}_H1_BA.csv` (2021-2026)
- trading-bridge : `docs/real-cost-model-hourly-spec.md`, branche `docs/real-cost-model-hourly-spec`
