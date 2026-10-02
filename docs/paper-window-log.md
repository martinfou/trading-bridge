# Journal des fenêtres d'observation paper

Une fenêtre commence à un déploiement et dure 30 jours. Les transactions antérieures au
déploiement sont **exclues** (décision D34), et elles sont listées ici pour que l'exclusion soit
vérifiable plutôt que déclarative. Le `lastTransactionID` au départ est la référence : toute
transaction dont le numéro est supérieur appartient à la fenêtre, et rien d'autre.

---

## Fenêtre 1 — démarrée le 2026-10-01 à 18:31 EDT (heure de démarrage des runners)

| Champ | Valeur |
|---|---|
| Commit déployé | `d4329edf` (master) |
| Compte | `101-002-4729622-014` (OANDA practice, CAD) |
| NAV au départ | **1981.3922 CAD** |
| `lastTransactionID` au départ | **218** |
| Positions ouvertes | 0 |
| Ordres en attente | 0 |
| Fin prévue | 2026-10-31 |

**Décision D38 (Martin, 2026-10-02) — la fenêtre 1 continue à travers le correctif du cooldown.**
Le correctif de la story `47-1-cooldown-non-arme-apres-sortie-courtier.md` se déploie **sans interrompre la
fenêtre** : ni la stratégie, ni le sizing, ni l'instrument, ni la liste des services ne changent, seul un
défaut de comportement est corrigé. L'horloge reste celle du 2026-10-01 18:31 EDT, fin prévue 2026-10-31,
et la date de déploiement du correctif est inscrite ci-dessous pour que la période avant/après reste
lisible plutôt que mélangée.

| Correctif | Déployé le |
|---|---|
| 47.1 — cooldown non armé sur sortie courtier | _(à remplir au déploiement)_ |

Services en marche et instrument effectivement utilisé (lu dans les journaux de démarrage, pas
dans la config) :

| Service | Stratégie | Instrument | Source |
|---|---|---|---|
| `trader` | consecbar | GBP_JPY | `config strategies.consecbar.instrument` |
| `trader` | vwpreversion | **USD_CHF** | `config strategies.vwpreversion.instrument` |
| `month-week` | monthweekphase | USD_JPY | config |
| `comp-momentum` | compmomentum | USD_JPY | config |
| `lt-rsi3` | ltrsi3 | EUR_USD | config |

`vwpreversion` mérite d'être noté : il tradait **GBP_JPY** avant ce déploiement, parce que
l'instrument était résolu depuis le nom d'affichage de la stratégie et retombait sur un défaut
GBP_JPY. La valeur était censée être USD_CHF depuis toujours. C'est ce bug qui a produit la perte
de la ligne ci-dessous.

### Transactions EXCLUES de la fenêtre (antérieures au déploiement)

| Heure (EDT) | Stratégie | Instrument | P&L |
|---|---|---|---|
| 2026-10-01 | consecbar | GBP_JPY | −15.02 |
| 2026-10-01 | vwpreversion | GBP_JPY | −11.72 |
| 2026-10-01 | ltrsi3 | EUR_USD | +9.95 |
| 2026-10-01 13:26 → 13:48 | suite de tests (tags UUID) | EUR_USD | −1.81 |
| | | **Total exclu** | **−18.60** |

Ce total explique la baisse du NAV depuis 2000 (**18.6078**) à 0.01 près : il n'y a **pas** de financement.
La ligne de la suite de tests est une correction apportée le 2026-10-02 : ces 8 fills réels étaient
jusqu'ici absorbés dans l'écart attribué au financement (voir l'incident ci-dessous).

### Transactions DANS la fenêtre

| Heure (EDT) | Stratégie | Instrument | Sens | P&L |
|---|---|---|---|---|
| 2026-10-01 22:00 | consecbar | GBP_JPY | BUY | −12.25 |
| 2026-10-02 03:00 | vwpreversion | USD_CHF | BUY | −12.14 (stop courtier) |
| 2026-10-02 04:00 | vwpreversion | USD_CHF | BUY | ouvert (SL 0.82521) |

Total réalisé dans la fenêtre : **−24.39 CAD**. NAV au 2026-10-02 08:56 UTC : **1961.70** (balance
1957.00, position ouverte +4.69).

La séquence `vwpreversion` est le premier écart backtest-vers-live documenté de cette fenêtre : la
première entrée est sortie par le stop du courtier à 03:57:37, et la seconde est prise **3 minutes plus
tard, sur la barre suivante, dans le même sens**. Le cooldown de 10 barres de la stratégie n'a pas été
armé, parce qu'il n'est armé que par la sortie locale de la stratégie, jamais par une sortie subie.
Story ouverte : `_bmad-output/implementation-artifacts/47-1-cooldown-non-arme-apres-sortie-courtier.md`.

---

## Ce que ce déploiement a changé

- **L'instrument vient de la config explicite**, plus du nom d'affichage. Le journal de démarrage
  nomme la source, donc la question « sur quoi trade-t-il vraiment ? » a une réponse lisible.
- **Le stop est attaché à l'ordre d'entrée** (`stopLossOnFill`) au lieu d'un second appel après le
  fill, et une garde refuse toute entrée sans stop : pas de stop, pas de taille.
- **La classe de tests `RunManagerTest` est hermétique** : zéro appel courtier, et le test de
  concurrence prouve l'exclusion mutuelle (il échoue 3 fois sur 3 quand on neutralise le verrou).
- **Le fil-piège des ordres est actif** : un ordre ne quitte la JVM que si `TB_ALLOW_ORDERS` vaut
  exactement `1`/`true`, et jamais depuis un runtime de test. Les 5 services portent le drapeau.

## Incident du 2026-10-01, à garder en tête pendant la fenêtre

La suite de tests a envoyé **12 ordres réels** sur ce compte le jour même (12 `MARKET_ORDER`
EUR_USD `units=-1000`, tickets 195-217), tous annulés avec `STOP_LOSS_ON_FILL_LOSS`, **aucun
fill**. Le compte n'a pas été touché **par chance** : le stop attaché venait de 10 barres
synthétiques, donc OANDA l'a rejeté. Avec un stop plausible, ces 12 ordres se remplissaient.

C'est la raison du fil-piège, et c'est aussi la raison pour laquelle le `lastTransactionID` de
départ est noté ici : c'est le seul chiffre qui permet de dire, sans confiance aveugle, si quelque
quelque chose a envoyé un ordre qui n'aurait pas dû.

**Correction du 2026-10-02 — la fuite a touché le compte deux fois ce jour-là, pas une.** Les 12 ordres
du paragraphe ci-dessus (tickets 195-217) sont bien restés sans fill parce que le stop attaché venait de
barres synthétiques. Mais la même signature d'ordres (tag UUID, EUR_USD, `units=-1000`, par salves) a
**rempli 8 fois** plus tôt dans la journée, entre 17:26 et 17:48 UTC : chaque position a été refermée
dans la seconde, à −0.14/-0.26, pour un total de **−1.81** (trades 29, 48, 62, 76, 90, 104, 118, 132).
Ce n'est donc pas la chance qui a protégé le compte, c'est le format du stop : là où le stop était
plausible, l'ordre s'est rempli. Le total exclu de la fenêtre les inclut maintenant.

---

## Fenêtre 2 — décidée le 2026-10-01, pas encore démarrée

**Décision de Martin (2026-10-01, ~00:15 EDT)** : après rejeu du shortlist sous le vrai modèle de coûts,
la fenêtre doit observer **« Gold vendredi long × DXY opposé »** (XAU_USD), et l'horloge de 30 jours
redémarre à son déploiement (règle D34).

Pourquoi ce candidat : au rejeu aux **deux niveaux de spread** (médiane mesurée et médiane × 1,5), il
tient **toutes** les fenêtres à PF ≥ 1.17 **même au niveau conservateur**, avec un net positif partout et
un DD ≤ 6.55 %. Rejeu reproduit par l'orchestrateur lui-même, cellule par cellule
(`research/shortlist-real-costs`, commit `29fdbfbb`) :

| Fenêtre | PF méd. | Net$ méd. | PF ×1,5 | Net$ ×1,5 | Trades |
|---|---|---|---|---|---|
| FULL | 1.28 | +4 022 | 1.23 | +2 774 | 471 |
| IS | 1.23 | +756 | 1.17 | +88 | 252 |
| OOS1 | 1.40 | +1 398 | 1.34 | +1 072 | 123 |
| OOS2 | 1.28 | +1 869 | 1.25 | +1 614 | 96 |

### Ce qui bloque encore ce déploiement (constaté, pas supposé)

1. **L'indice dollar n'existe pas dans le chemin live.** Les trois candidats survivants en dépendent :
   `GoldWeekdayDxyStrategy(name, symbol, Map<Long,Double> dxy)`. Le DXY est **synthétisé en code** depuis
   cinq paires (EUR_USD 0.576, USD_JPY 0.136, GBP_USD 0.119, USD_CAD 0.091, USD_CHF 0.036, facteur
   `K = 50.14348112`), toutes servies par OANDA — donc reproductible fidèlement en live, mais il faut un
   fournisseur et un chemin d'injection : `LiveStrategyRunner` ne tire **qu'un seul instrument**
   (`priceClient.getCandles(oandaSymbol, granularity, …)`) et rien dans `config/` ne mentionne DXY. La
   stratégie lit le DXY de la barre **strictement antérieure** : le flux live ne doit servir que des
   barres fermées, sinon on introduit un look-ahead que le backtest n'avait pas.
2. **Le stop protecteur ne doit pas changer la stratégie.** La garde du moteur refuse toute entrée sans
   stop (D30), or une stratégie de session vendredi sort sur le **temps**, pas sur un stop. Le stop ATR
   attaché doit donc être **prouvé non liant** en mesurant la pire excursion adverse du backtest, avec un
   rejeu qui confirme que les chiffres ne bougent pas. Attacher un stop sans cette preuve remplacerait
   silencieusement la stratégie validée par une autre.
3. **Les verdicts du shortlist sont provisoires** jusqu'au rejeu au sizing imposé par D37 (1 % de risque
   par transaction, capital identique).

Les deux premiers points forment une histoire BMad (spec, story, revue Elliot + les deux passes agy,
déploiement), pas une entrée de configuration.
