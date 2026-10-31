# Story — Le journal de fills perd les sorties après un redémarrage

- **Status :** `ready-for-dev` (spécification seule — **aucun code modifié**, aucun déploiement fait)
- **Date :** 2026-10-31
- **Propriétaire :** Martin
- **baseline_commit :** `313cd704` (HEAD master, 2026-10-30 11:04 EDT)
- **Origine :** `docs/paper-window-log.md`, « Défaut mesuré : le journal de fills ne contient que les entrées »
  (relevé du jour 1 de la fenêtre 3), point ouvert n° 1 de la lane futures
- **Chemin touché :** chemin de l'attribution des ordres (`IbkrBroker`, `TcpIbkrGatewayClient`) → revue
  **Elliot** + deux passes `agy`, déploiement avec état sauvegardé puis restauré

## 1. Story

**En tant qu'**opérateur de la fenêtre paper 3 (lane futures IBKR),
**je veux** que chaque exécution du courtier devienne une ligne du journal de fills, même si elle concerne un
ordre créé par un processus précédent,
**afin que** la fenêtre puisse produire elle-même la preuve de P&L que D46 exige, sans dépendre d'une lecture
manuelle du courtier.

## 2. Ce qui a été mesuré, pas supposé

Le courtier a exécuté **5 fois** le 2026-10-30 ; le journal
`data-futures/fills/sq-mgc-m30-breakout-0-24542-MGC-paper.jsonl` porte **3 lignes**, toutes des entrées.

| Heure (UTC) | `orderId` | Sens | Qté | Prix | Rôle | Dans le JSONL |
|---|---|---|---|---|---|---|
| 04:30:07 | 1000 | SLD | 1 | 4003,60 | entrée | **oui** (04:30:08) |
| 12:00:05 | 1003 | SLD | 1 | 3975,40 | entrée | **oui** (12:00:05) |
| 12:14:22 | 1002 | BOT | 1 | 3969,90 | sortie (TP du trade 1) | **non** |
| 13:33:21 | 1005 | BOT | 1 | 3943,20 | sortie (TP du trade 2) | **non** |
| 14:30:05 | 1006 | SLD | 1 | 3916,70 | entrée | **oui** (14:30:05) |

Le déclencheur exact, vérifié : le processus `IbkrLiveRunner` (PID 955087) a démarré le
**2026-10-30 08:10:30 EDT**, soit **12:10:30 UTC** — entre l'entrée 1003 (12:00:05) et sa sortie 1002
(12:14:22). Les deux brackets 1001/1002 et 1004/1005 ont donc été créés par un processus qui n'existe plus au
moment où leurs sorties se remplissent.

**État revérifié le 2026-10-31 11:00 EDT :** le journal porte toujours 3 lignes, aucune ligne `BUY`, et le PID
955087 tourne toujours. La lacune n'est pas un retard d'écriture : elle est stable.

Ce que ce défaut ne touche pas, dit pour ne pas le confondre avec un incident d'argent : le miroir P&L est
alimenté depuis le courtier par `scripts/ibkr_pnl_push.py` (clientId 98, `reqExecutions` +
`CommissionReport.realizedPNL`), pas depuis ce JSONL. **Le registre est faux, le compte est juste.**

## 3. Mécanisme, lu dans le code

### 3.1 La corrélation ordre → stratégie ne vit qu'en mémoire

| Où | Quoi |
|---|---|
| `trading-data/.../TcpIbkrGatewayClient.java:84` | `orderIdToClientTag` — `Map<Integer,String>` alimentée à la pose d'ordre (`:395`) |
| `trading-data/.../TcpIbkrGatewayClient.java:711-712` | `execDetails` lit `clientTag` **dans cette map** ; après redémarrage, elle est vide ⇒ `clientTag = null` |
| `trading-broker/.../IbkrBroker.java:30-33` | `pendingOrdersByClientTag`, `pendingOrdersByBrokerId`, `filledQuantityByOrderId` — toutes en mémoire |
| `trading-broker/.../IbkrBroker.java:55-63` | `onExecution` tente `clientTag`, puis `orderId` ; les deux ratent |
| `trading-broker/.../IbkrBroker.java:80` | branche `else` : `log.warn("IBKR fill for unknown order … reconciliation needed")` et **aucun `BrokerEvent.FILL`** |
| `trading-runtime/.../IbkrLiveRunner.java:342` | le JSONL n'est écrit que sur `BrokerEventType.FILL` |

Chaîne complète : ordre inconnu ⇒ pas d'événement ⇒ pas de ligne. Le `warn` est la seule trace, et il part
dans les logs d'un processus dont la sortie standard n'est pas lisible depuis le compte `administrator`
(`/proc/955087/fd` non accessible) : il n'existe donc aucune trace consultable a posteriori.

### 3.2 L'information manquante est pourtant livrée par le courtier

`trading-data/src/main/java/com/ib/client/Execution.java:20,43` : l'objet `Execution` d'IBKR porte
**`orderRef`**, et `orderRef` = nom canonique de la stratégie (posé ainsi par
`IbkrOrderDispatcher.java:117-122` sur les trois jambes du bracket : entrée, stop, take-profit). Le
`ibkr_pnl_push.py` s'en sert déjà (ligne 363 : `getattr(execution, "orderRef", "")`).

Le défaut est donc **une donnée jetée à la frontière** : `TcpIbkrGatewayClient.mapExecution` (`:550-555`)
construit `IbkrExecution` (record à 9 champs) **sans `orderRef`**, alors que la valeur est dans la main.

### 3.3 Deux autres maps se vident au même moment, avec des conséquences visibles

1. `IbkrLiveRunner.requestedPricesByOrderId` (`:333`) est remplie par `ORDER_SUBMITTED` : après redémarrage,
   une sortie retrouvée n'a pas de prix demandé. `onBrokerEvent` (`:345-352`) prend alors `filledPrice` comme
   prix demandé, ce qui produirait un **slippage mesuré à 0** — une mesure fausse, pas une absence de mesure.
2. `filledQuantityByOrderId` cumule les remplissages partiels : reparti à zéro, un ordre partiellement rempli
   avant le redémarrage serait purgé sur le premier partiel suivant (le `cumulative + 1e-9 >= order.quantity()`
   de `:76` comparerait un cumul tronqué à la quantité totale).

## 4. Correction demandée

Deux niveaux, volontairement séparés. Le premier est le correctif ; le second est la preuve.

### 4.1 Niveau 1 — ne plus jamais perdre un fill (le correctif)

Une exécution de remplissage qui ne se résout par aucun registre local **est attribuée par `orderRef`** :

1. `IbkrExecution` gagne un composant `orderRef` ; `mapExecution` le renseigne depuis
   `execution.orderRef()`. Aucun appel courtier supplémentaire, aucun état persisté.
2. `IbkrBroker.onExecution`, branche « ordre inconnu » :
   - `orderRef` non vide ⇒ émettre un `BrokerEvent.fill` avec `strategyId = orderRef`, `orderId` = identifiant
     d'ordre du courtier, quantité = quantité **du partiel**, prix = prix de fill. C'est un fill réel du
     courtier : l'événement ne fabrique rien.
   - `orderRef` vide ou non nommé ⇒ émettre quand même le fill avec `strategyId = "unattributed"`, compter
     l'incident (compteur exposé au moniteur) et journaliser en **ERROR**, pas en `warn`. Une exécution ne
     disparaît plus ; son attribution, elle, peut rester inconnue et doit être visible.
3. Le `warn` actuel reste mais ne peut plus être la **seule** trace : il devient le message d'accompagnement
   de l'événement émis.
4. Prix demandé inconnu ⇒ `requestedPrice = 0.0` dans la ligne. `FillLogRecord.computeSlippage` renvoie déjà
   `0.0` quand `requestedPrice <= 0`, et la convention est écrite dans la story plutôt que dans le code : une
   ligne de sortie avec `requestedPrice = 0` signifie « prix demandé inconnu », **pas** « slippage nul mesuré ».
5. Cumul des partiels : quand l'ordre est inconnu, le cumul repart de la quantité du fill courant sans purge
   possible (pas de quantité totale connue). Le fill est journalisé, l'ordre n'est pas purgé — c'est le
   comportement correct : on ne sait pas s'il est complet.

Aucune écriture sur disque, aucune persistance : la map reste volatile, mais son absence n'est plus une perte.

### 4.2 Niveau 2 — reconstituer la preuve de la fenêtre (le rattrapage)

Le correctif du niveau 1 ne répare pas rétroactivement les **deux sorties déjà perdues** (1002, 1005), et une
fenêtre de 30 jours subira d'autres redémarrages. Un rattrapage **en lecture seule**, sur le modèle de
`ibkr_pnl_push.py` (clientId 98/97, `reqExecutions`, aucun ordre envoyé) :

- rejouer les exécutions depuis la frontière `orderId >= 1000` (frontière de la fenêtre 3) ;
- comparer `execId` par `execId` au JSONL existant ;
- écrire les lignes manquantes avec `requestedPrice = 0.0`, `side`/`quantity`/`filledPrice` du courtier,
  `strategy` = `orderRef`, et un marqueur explicite dans le nom de fichier ou la ligne ;
- être idempotent : relancer deux fois ne duplique rien.

C'est ce qui rend la fenêtre 3 capable de se juger elle-même (D46) **sans** attendre le correctif ni un
redémarrage.

### 4.3 Ce qui n'est pas dans cette story

- **`fetchOpenOrders` / `reqAllOpenOrders`** : l'interface `IbkrGatewayClient` n'expose aucune requête d'ordres
  ouverts (seulement `fetchAccountSummary` et `fetchOpenPositions`), donc le hub ne peut pas afficher les stops
  protecteurs (point ouvert n° 2 du journal de fenêtre : `ibkr_snapshot_push.py:235` pose
  `"open_orders": []` en dur). Le correctif est le même objet (le registre des ordres), mais il change
  l'interface du client et le payload du hub : **story séparée**, pas un passager clandestin ici.
- Aucune alerte Telegram nouvelle (point ouvert n° 3) : le compteur d'exécutions non attribuées de 4.1.2 est
  le minimum visible ; la politique d'alerte se décide avec le reste du chemin d'alerte.
- Aucun changement de stratégie, sizing, instrument ou liste de services.

## 5. Critères d'acceptation

- [ ] **Redémarrage entre l'entrée et la sortie** : un `IbkrBroker` neuf (donc registres vides) reçoit une
      exécution de l'ordre de take-profit d'un bracket posé par l'instance précédente ⇒ un `BrokerEvent.FILL`
      est émis **et** une ligne atterrit dans le JSONL, avec la bonne stratégie et le bon sens (BUY).
- [ ] **`orderRef` absent** : aucune stratégie n'est inventée ; la ligne porte `unattributed`, l'incident est
      compté et journalisé en ERROR. Une exécution sans attribution n'est jamais silencieuse.
- [ ] **Prix demandé inconnu** : `requestedPrice = 0.0`, `computedSlippage = 0.0`, et la convention est
      documentée (pas de slippage « mesuré » fabriqué).
- [ ] **Fills partiels après redémarrage** : deux partiels sur un ordre inconnu produisent deux lignes, aucune
      purge prématurée, aucun cumul faux.
- [ ] **Pas de doublon** : le même `execId` livré deux fois (ou `execDetails` suivi de `orderStatus=FILLED`)
      n'écrit qu'une ligne — la garde `notifiedExecIds`/`filledOrderIds` du client reste la seule autorité.
- [ ] **Rattrapage idempotent** : rejoué deux fois sur `data-futures/fills/`, il n'ajoute que les `execId`
      absents ; sur l'état mesuré du 2026-10-30 il ajoute exactement 2 lignes (1002, 1005).
- [ ] **Test de mutation** : retirer l'attribution par `orderRef` fait échouer le premier test. Un test qui
      passe sans le correctif est de la décoration.
- [ ] **Non-régression** : les 3 lignes existantes restent lisibles par `FillLogWriter.readAll` et
      `FillLogComparisonReport` ; aucun champ retiré ni renommé.

## 6. Décisions à prendre (une seule, par Martin)

Le correctif de 4.1 suppose qu'une exécution **non attribuable** doit quand même entrer au journal, avec
`strategy = "unattributed"`. L'alternative est de la refuser et de compter un incident. Le choix change ce que
la fenêtre peut prouver :

| Option | Conséquence | Risque |
|---|---|---|
| **A. Journaliser `unattributed` (recommandé)** | le total des fills de la fenêtre est toujours complet ; l'attribution se vérifie à part | une ligne sans stratégie dans un rapport par stratégie, à filtrer explicitement |
| B. Refuser et compter | aucune ligne ambiguë ; le total de la fenêtre reste incomplet | revient à la situation actuelle pour la preuve D46 |

Le nom `unattributed` est proposé pour ne pas collisionner avec `leak`, que `ibkr_pnl_push.py:117` réserve au
cas « `orderRef` absent ou en UUID » côté P&L publié.

## 7. Risque et forme du changement

Le chemin touché est celui de l'attribution d'un **événement**, pas celui de l'**envoi** d'un ordre : aucune
signature de `submitOrder` ne change, aucune taille, aucun prix. Le risque est donc plus bas que la story
`spec-exit-path-closes-foreign-position.md`, mais il reste sur le chemin des ordres : revue **Elliot** + deux
passes `agy`, et déploiement séparé avec état sauvegardé puis restauré (procédure
`trading-bridge-live-operations` §5).

**La fenêtre 3 n'est pas interrompue** par ce correctif, par le précédent D38/D59 : ni stratégie, ni sizing, ni
instrument, ni liste de services ne changent, seul un défaut de registre est corrigé. La frontière
(`orderId >= 1000`) et l'horloge (fin 2026-11-28 22:04 EDT) restent celles du 2026-10-30, et la date du
déploiement du correctif est inscrite dans `docs/paper-window-log.md` pour que la période avant/après reste
lisible.

## 8. Preuves à joindre au déploiement

1. Suite complète verte (tous modules), plus les nouveaux tests nommés.
2. Après déploiement : provoquer/attendre au moins une sortie sur un bracket posé **avant** le déploiement et
   vérifier la ligne correspondante dans le JSONL, avec `strategy` égale à la stratégie du bracket.
3. Rattrapage exécuté une fois, sortie affichant « 2 lignes ajoutées (1002, 1005) », puis relancé une seconde
   fois affichant « 0 ligne ajoutée » (idempotence prouvée).
4. `git rev-list --count origin/master..master` = 0 après poussée.
