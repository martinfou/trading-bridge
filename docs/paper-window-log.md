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
| 47.1 − cooldown non armé sur sortie courtier | **2026-10-02 09:26 EDT**. Preuves : image créée à 13:26:09 UTC, `LiveStrategyRunner.class` compilée à 13:15:42 UTC, runners repris à 13:26:25 UTC (commit `81beb350`, 07:26 EDT) |
| 48.2 + garde-fous de déploiement | **2026-10-09 06:07 EDT**. Voir la section du 2026-10-09 en fin de fichier |

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

Mise à jour **2026-10-09 09:30 UTC** : la table couvre maintenant **toutes** les transactions de la
fenêtre (`id > 218`), pas seulement les trois premières. Le `lastTransactionID` de départ reste la
frontière : tout ce qui porte un numéro supérieur à 218 est ici, et rien d'autre.

| Heure d'entrée (EDT) | Stratégie | Instrument | Sens | P&L |
|---|---|---|---|---|
| 2026-10-01 22:00 | consecbar | GBP_JPY | BUY | −12.25 |
| 2026-10-02 03:00 | vwpreversion | USD_CHF | BUY | −12.14 (stop courtier) |
| 2026-10-02 04:00 | vwpreversion | USD_CHF | BUY | −13.17 (stop courtier) |
| 2026-10-02 06:00 | ltrsi3 | EUR_USD | SELL | −3.76 |
| 2026-10-04 19:00 | consecbar | GBP_JPY | SELL | +20.02 |
| 2026-10-05 08:00 | ltrsi3 | EUR_USD | SELL | −0.79 |
| 2026-10-07 04:00 | vwpreversion | USD_CHF | SELL | −9.14 (stop courtier) |
| 2026-10-07 09:00 | ltrsi3 | EUR_USD | SELL | −2.74 |
| 2026-10-08 01:00 | ltrsi3 | EUR_USD | SELL | +6.40 |
| 2026-10-08 14:00 | vwpreversion | USD_CHF | BUY | −9.34 (sortie sur signal) |
| 2026-10-09 05:00 | ltrsi3 | EUR_USD | SELL | **ouverte** (stop 1.12341) |

Total réalisé dans la fenêtre : **−36.91 CAD** (vwpreversion **−43.78** sur 4 sorties,
ltrsi3 **−0.90** sur 4 sorties, consecbar **+7.77** sur 2 sorties ; `monthweekphase` et
`compmomentum` n'ont pas encore tradé. NAV au 2026-10-09 09:30 UTC : **1944.06** (balance 1944.49,
1 position ouverte à −0.43). Le NAV de départ (1981.39) moins ce total donne exactement la balance :
aucun financement. Point de passage précédent, conservé : NAV **1961.70** au 2026-10-02 08:56 UTC,
total alors de **−24.39**.

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

## 2026-10-02, 19h00 EDT : panne OANDA, la fenêtre est en pause forcée

Constat mesuré, pas supposé :

- `api-fxpractice.oanda.com/v3/accounts` **sans jeton** répond 401 : le service est joignable et
  l'authentification s'exécute. Mais **toute requête authentifiée de compte renvoie 503 Service Unavailable
  en environ 70 ms**, mesuré sur **deux comptes distincts** (-014 papier et -013 dev). Ce n'est donc ni notre
  clé, ni notre compte, ni un blocage d'IP : c'est le service practice qui est dégradé.
- La page d'état d'OANDA annonce une **maintenance non planifiée** touchant son système d'authentification
  (capture transmise par Martin le 2026-10-02).
- **Conséquence sur la fenêtre :** les runners ne récupèrent plus de nouvelle barre. Dernier cycle traité à
  **21h00 UTC** (barre de 16h00 EDT), soit deux cycles horaires manqués au moment du constat. Le moniteur
  continue de s'écrire, donc le runner est vivant mais aveugle : il ne prend plus aucune décision.
- **Aucune position ouverte** au moment de la panne (consecbar 2 entrées / 2 sorties, vwpreversion
  3 entrées / 3 sorties). Rien ne traîne sans surveillance : la fenêtre est en pause, pas en danger.
- Le dashboard web affichait tous ses tuiles à `$—` : c'est son état de repli prévu quand OANDA ne répond
  pas, pas un bug du hub. Ne pas chercher un défaut côté hermes-web si l'affichage se vide encore.
- **Le déploiement du correctif de durabilité des runs (48.2) est bloqué par cette panne** : le gate de
  pré-déploiement ouvre sur un appel OANDA en lecture, qui échoue en 503, et le script de déploiement
  vérifie ensuite l'état du compte chez le courtier. On ne déploie pas avant le retour de practice.
- La CI de ce dépôt, elle, n'appelle pas OANDA (tests unitaires seulement) : elle reste exploitable pendant
  la panne, ce qui a permis de corriger ses échecs sous Java 21 dans la même soirée.

## 2026-10-03, 11h00 EDT : practice est revenu, le blocage est levé (mesuré)

Le service practice répond de nouveau. Mesures faites le 2026-10-03 à 15h01 UTC, depuis ce poste :

- `GET /v3/accounts` avec le jeton papier : **HTTP 200** en 409 ms, et
  `GET /v3/accounts/101-002-4729622-014/summary` : **HTTP 200** en 55 ms. Même résultat sur le compte
  dev `-013` (HTTP 200). Le 503 authentifié décrit le 2026-10-02 n'est plus reproductible, sur aucun des
  deux comptes.
- Les bougies sont servies : `XAU_USD`, `GBP_JPY`, `USD_CHF` et `EUR_USD` en H1 répondent 200 en moins de
  80 ms. La dernière bougie complète est `2026-10-02T20:00Z` (vendredi) : c'est l'état normal d'un marché
  fermé un samedi, pas une séquelle de la panne.
- **Layer 3 du gate de pré-déploiement** (`scripts/smoke-oanda-readonly.py`) : **exit 0**, compte `-014`
  joignable, balance 1940.0742 / NAV 1940.0742, 0 position ouverte. C'est exactement le contrôle qui
  échouait en 503 la veille : le blocker de déploiement est levé.

Conséquences, et ce qui n'est pas décidé ici :

- Le déploiement du correctif de durabilité **48.2** n'est plus bloqué par OANDA. Il n'est pas fait pour
  autant : la story est encore **`draft` (propose-only)** dans master, son implémentation vit sur la branche
  `fix/run-48-2-durability` (commits `3fb4e0a4`, `a24810c7`) et n'est **pas fusionnée**. La décision de
  Martin reste requise avant tout déploiement.
- La fenêtre 1 reste **vivante** mais **aveugle** tant que le marché est fermé. Le processus
  `LiveStrategyRunner` (PID 12 dans `trading-live`, relancé le 2026-10-02 à 23h25 UTC) est toujours en
  boucle principale : sa dernière barre traitée est `2026-10-02T20:00Z` (vendredi 16h00 EDT), la dernière
  du vendredi, et ses erreurs de bougie se sont **arrêtées** à 12h40 UTC le 2026-10-03 (les 504 de practice
  ne se reproduisent plus). Elle reprendra d'elle-même à l'ouverture de la session, dimanche 21h00 UTC.
  Aucune position ouverte : rien ne traîne sans surveillance.

## 2026-10-09, 06:07 EDT : 48.2 et les garde-fous de déploiement sont déployés (la fenêtre 1 continue)

**Décision de Martin (2026-10-09)** : déployer maintenant, en fusionnant les deux branches sur `master`
et en déployant le papier **avec l'état préservé**. Ceci ferme explicitement l'ouverture laissée par la
note du 2026-10-03 (« la décision de Martin reste requise avant tout déploiement »). Ni la stratégie, ni
le sizing, ni l'instrument, ni la liste des services ne changent : **le rythme de la fenêtre reste celui
du 2026-10-01 18:31 EDT, fin prévue 2026-10-31**, même règle que D38 pour le correctif 47.1.

- **Fusion sur `master`** : `f363c6da` (48.2 : `RunManager`, `RunRecord`, `RunState`, `RunManagerTest`)
  puis `c4973635` (`scripts/deploy-paper.sh`), fusionnées en `--no-ff` depuis un worktree jetable, puis
  poussées sur `origin/master` (vérifié : `git rev-list --count origin/master..master` = 0, et les trois
  commits revus `3fb4e0a4`, `a24810c7`, `8d233bdb` sont ancêtres de `origin/master`).
- **Revue indépendante** : deux passes, verdicts NEEDS_FIX des deux côtés, tous les constats traités
  (1 BLOCKER, 2 MAJOR, 1 MINOR sur le Java ; 3 constats sur le script). Détail : story 48.2.
- **Preuve avant déploiement** : suite complète **verte sous Java 21** (la seule JVM de la matrice CI),
  **12/12 modules**, 3 min 20 ; puis le gate du script (build, suite sous Java 26, `docker compose config`,
  smoke OANDA en lecture) : **`✅ GATE PASSED`**.
- **Déploiement** : `scripts/deploy-paper.sh --apply`. L'état des 5 stratégies a été copié hors de chaque
  conteneur, restauré pendant que le nouveau conteneur était **à l'arrêt**, puis le conteneur a démarré.
  Vérification : **`✓ deployment verified (broker 1 = runners 1 sur 4 service(s) vivant(s))`**.
- **Ce que la fenêtre a conservé** (relevé après redémarrage, identique à l'avant) : consecbar 4 entrées /
  3 sorties / −7.25 ; vwpreversion 5 / 5 / −55.51 ; ltrsi3 6 / 5 / +9.06 **avec la position
  279 reprise** (`inTrade: true`, statut `CONFIRMED`) ; `monthweekphase` et `compmomentum` intacts à 0.
  Le curseur de barres a été maintenu à la fin du warm-up et les ordres que le warm-up avait mis en file
  ont été jetés (14 pour consecbar, 8 pour vwpreversion) : aucun rejeu historique n'a tradé.
- **Aucun ordre envoyé par le déploiement** : `lastTransactionID` **280** avant comme après, balance
  inchangée à **1944.4867**, `openTradeCount` 1 (trade 279, SL 1.12341). C'est la preuve que le
  déploiement n'a pas touché l'argent.
- **L'image déployée est bien celle-ci** : `LiveStrategyRunner.class` compilée le 2026-10-09 10:03 UTC
  (et non celle du 2026-10-02), et `RunManager.class` contient `liquidation_failed`, la clé de charge
  utile introduite par 48.2.

### Un signal a été perdu le 2026-10-08 à la bascule de 17h00 ET (consecbar, GBP_JPY)

La transaction `MARKET_ORDER` du 2026-10-08 21:00:24 UTC (tag `consecbar_GBP_JPY`) a été annulée par le
courtier avec le motif `MARKET_HALTED`. Le journal du runner donne la cause réelle, qui n'est pas un
marché fermé pour la journée :

```
21:00:24.269 ═══════ ENTRY GBP_JPY BUY 0.05 lots @ N/A (stop on fill: 208.632) ═══════
21:00:24.270 ❌ TRADE EXECUTION FAILED: GBP_JPY BUY @ 208.959 — For input string: "N/A"
```

Le prix courant est revenu **`N/A`** : la bascule quotidienne d'OANDA à 17h00 ET suspend brièvement la
cotation, et la barre qui a déclenché le signal est justement celle qui tombe sur cette minute. L'entrée
s'est terminée sur une `NumberFormatException` au lieu d'être sautée proprement. **Aucun risque encouru** :
rien n'a rempli, `openTradeCount` n'a pas bougé, aucune position n'est restée sans surveillance. Un signal
est simplement perdu, et le message d'erreur ne dit pas pourquoi. À corriger dans la famille « une entrée
sans prix doit être sautée, pas planter ».