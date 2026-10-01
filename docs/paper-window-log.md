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
| | | **Total exclu** | **−16.79** |

L'écart entre ce total et la baisse du NAV depuis 2000 (18.61) est du financement.

### Transactions DANS la fenêtre

_(aucune pour l'instant)_

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
chose a envoyé un ordre qui n'aurait pas dû.
