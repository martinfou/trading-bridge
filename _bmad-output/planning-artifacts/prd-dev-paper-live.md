# PRD — Environnements dev / paper / live pour le trading bridge

> **Track** : BMad Method — Phase 2 (Planning)
> **Auteur** : Hermes, à partir des décisions de Martin (Product Owner)
> **Statut** : BROUILLON, en attente des réponses aux questions ouvertes
> **Date** : 2026-09-30

## 1. Problème

Cinq services Docker tournent depuis un même dépôt, tous branchés sur le **même compte OANDA**
(`101-002-4729622-012`), tous construits depuis le **même code**. Le découpage se présente comme une
séparation mais n'en est pas une :

| Constat mesuré (2026-09-30) | Valeur |
|---|---|
| Mémoire des 4 conteneurs actifs | 253 MiB au total (83,5 + 59,3 + 54,6 + 55,9) |
| CPU au repos | 0,06 % chacun |
| Images du même code | 5, dont 3 vieilles de deux mois |
| Comptes OANDA distincts | 1 seul pour les 5 services |

Les ressources ne sont donc pas le problème. Le problème est la **dérive silencieuse** : parce que
chaque service a sa propre image, trois conteneurs ont continué à trader avec un code de juillet
pendant que le quatrième recevait les correctifs. Le correctif de sizing risquait risque de n'être
déployé que sur un seul des quatre, et rien n'aurait signalé les trois autres.

Conséquence secondaire, du même défaut : un correctif déployé sans moyen de le valider sur des
exécutions réelles. Le 2026-09-30, la première mise en service du sizing correct a coûté 494 CAD en
sept secondes sur le compte de production, parce qu'il n'existait aucun endroit où l'exécuter d'abord.

## 2. Vision

Trois environnements, **une seule image**, séparés par **l'axe compte**, pas par stratégie :

| Environnement | Compte | Rôle | Peut perdre de l'argent réel |
|---|---|---|---|
| **dev** | Sous-compte practice jetable (à créer) | Valider les vraies exécutions, casse libre, remis à zéro sans conséquence | Non |
| **paper** | Compte practice | Stratégies en cours de validation, observation prolongée | Non |
| **live** | Compte réel (à créer) | Stratégies prouvées uniquement | **Oui** |

## 3. Décisions déjà prises (Martin, 2026-09-30)

- **D1** — Trois environnements : dev, paper, live. *(choix explicite, question 1)*
- **D2** — dev trade un **sous-compte practice jetable** pour tester les vraies exécutions, au lieu
  d'un environnement qui ne ferait que compiler et tester. Un mock ne reproduit pas le comportement
  qui a coûté 494 CAD ; seule une exécution réelle le fait.
- **D3** — Le PRD et les stories précèdent toute modification du `docker-compose.yml` (track BMad
  Method, décision explicite de Martin sur la méthode).
- **D4** — Aucun déploiement sans revue indépendante et tests (règle permanente, rappelée par le
  skill BMad : « skipping code review is a workflow error »).
- **D5** — Ordre de construction : l'architecture se bâtit **maintenant**, avec dev sur un sous-compte
  practice jetable, et le service `live` **défini mais non démarré**. On ne bloque pas la
  structuration sur la création des comptes.
- **D12** — Une fois le token live créé par Martin, sa validité est vérifiée par **un seul appel de
  lecture** (résumé de compte), et rien d'autre. Aucun ordre, aucune écriture, aucun test d'exécution
  sur le compte réel : la garde et les tests d'incohérence s'exercent contre des configurations
  factices, jamais contre le compte. Un appel de lecture répond à la seule question qui compte (« le
  token ouvre-t-il bien ce compte ? ») sans créer de risque.
- **D10** — `nfp-week` va en **paper**, comme les trois autres services LT. Le compose ne le démarre
  toujours pas : il reste défini, et il rejoint l'environnement paper quand on le lancera.
- **D11** — Le compte de l'environnement **live** est `001-002-1889378-005` (Martin, 2026-09-30).
  Le préfixe `001` le distingue des `101-...` qui sont les comptes practice : celui-ci porte de
  l'argent réel. Cela corrige la section 4 du présent PRD, qui affirmait qu'aucun compte réel
  n'existait. Deux conséquences :
  - un **token propre au live** est nécessaire (un token practice ne donne pas accès à un compte réel,
    et un token par environnement est justement l'invariant de la section 5) ;
  - l'hôte de l'API diffère, donc le couple environnement / endpoint fait partie de ce que la garde
    doit vérifier au démarrage.
  Tant que le token live n'est pas en place et que Martin n'a pas explicitement autorisé un appel,
  **aucun travail d'Hermes ne touche ce compte**, même en lecture : c'est un compte réel.
- **D9** — L'état vit dans **un volume par environnement** (`strategy-state-dev`, `-paper`, `-live`),
  avec un fichier JSON par stratégie à l'intérieur, exactement la forme actuelle mais hors de `/tmp`.
  Le plus simple des trois choix, et il suffit : il supprime la perte d'état à chaque recréation, donc
  la danse manuelle de sauvegarde et restauration qui a failli perdre la position GBP_JPY ce soir, et
  qui a silencieusement gelé le fichier de monitorage du trader. Un volume par stratégie a été écarté
  comme sur-ingénierie à ce stade : le risque réel est la perte à la recréation, pas la contamination
  entre stratégies, qui partagent déjà le même compte et le même processus.
- **D8** — `lt-rsi3` (EUR_USD), `comp-momentum` et `month-week` vont en **paper**, pas en dev : ce sont
  des stratégies suivies sérieusement, pas des bacs à sable. Elles restent donc sur un compte practice
  et gardent leur historique. Conséquence à trancher : `-012` est aujourd'hui le compte practice
  principal (alias « trading bridge 2k », ~95 800 CAD) et tout y tourne, y compris le trader. Dans la
  cible, c'est lui le compte de l'environnement paper, et dev prend un sous-compte jetable distinct.
  Aucune stratégie n'est en live aujourd'hui : l'environnement live démarre vide.
- **D7** — Promotion **proposée, jamais exécutée** : une règle mesurée (durée en environnement,
  nombre de trades, P&L) calcule si une stratégie a gagné sa place dans l'environnement suivant, et
  le système le PROPOSE. Martin confirme. Une stratégie ne change jamais d'environnement toute seule :
  le passage vers le compte réel reste une décision humaine, parce que c'est la seule étape où une
  erreur coûte de l'argent réel. Le critère mesuré sert à éviter la promotion par impression, pas à
  remplacer la décision.
- **D6** — La création des deux comptes OANDA manquants appartient à Martin : l'API OANDA n'expose
  aucun moyen de créer un sous-compte, et cela demande une connexion à son interface. L'architecture
  les référence donc comme prérequis, avec l'emplacement de configuration prêt et marqué comme tel.
  Aucun travail d'Hermes ne doit supposer qu'ils existent déjà.

## 4. Le problème des comptes, énoncé franchement

Les trois sous-comptes existants sont pris : `-008` (health-dashboard), `-011` (hermes-web),
`-012` (le bridge, celui que les conteneurs tradent). L'architecture cible en demande deux de plus :

- un **sous-compte practice jetable** pour dev (il sera cassé volontairement, il ne doit rien
  partager avec ce qui compte) ;
- un **compte réel** pour live (il n'en existe aucun aujourd'hui : tout tourne sur practice).

Ces deux créations sont du ressort de Martin dans l'interface OANDA. Tant qu'elles n'existent pas,
l'architecture peut être construite et le service `live` défini mais non démarré.

## 4bis. Vérification du compte live (2026-09-30, D12 appliquée)

Un seul appel de lecture (`GET /v3/accounts/001-002-1889378-005/summary` sur `api-fxtrade.oanda.com`),
autorisé explicitement, a confirmé que le token live ouvre bien ce compte :

| Champ | Valeur |
|---|---|
| id | `001-002-1889378-005` |
| alias | `OANDA-live` |
| devise | CAD |
| balance / NAV | 2 000,0000 CAD |
| positions ouvertes / trades | 0 / 0 |
| HTTP | 200 |

**Implication de sizing à traiter dans l'architecture** : sur 2 000 CAD de NAV, un budget de risque de
0,75 % vaut 15 CAD par transaction. Combiné à un plancher de 2 000 unités en cas d'absence de budget
(`NO_RISK_UNITS_CAP`), c'est le genre de rapport qui produit soit des positions microscopiques, soit un
plancher qui dépasse le budget réel. À traiter explicitement plutôt que de laisser le moteur décider.

**Le token live a été transmis par la messagerie**, donc il doit être considéré comme exposé : il figure
dans l'historique de la conversation et dans les journaux de session. Il est stocké en `.env.live`
(mode 600, ignoré par git, vérifié), mais la mesure correcte est de le régénérer dans l'interface OANDA
et de déposer le nouveau **directement dans le fichier sur la machine**, sans passer par un message.

- **D13** — Le **plancher de taille devient proportionnel à la NAV**, calculé et affiché, jamais fixe.
  Le `NO_RISK_UNITS_CAP = 2 000` constant est remplacé par un pourcentage de NAV (ex. 0,5 %, soit
  10 CAD de risque sur un compte de 2 000 CAD), et la valeur retenue est écrite dans le journal au
  moment où elle s'applique. Raison : un plancher constant est juste sur un compte et faux sur un
  autre, alors qu'un plancher proportionnel est juste par construction. Sur le compte live à 2 000 CAD,
  l'ancien plancher engageait plus de risque que le budget entier qu'il était censé remplacer.

- **D14** — Seuils de proposition de promotion : **30 jours + 30 trades + P&L net positif** dans
  l'environnement courant. Les trois conditions sont nécessaires, aucune n'est suffisante. 30 trades
  est le plancher qui distingue une stratégie d'une série chanceuse, et 30 jours empêche qu'une bonne
  semaine déclenche une proposition. Le système propose, Martin confirme (D7), et rien ne se déplace
  automatiquement vers l'argent réel.

- **D15** — Les **2 000 CAD du compte live sont le capital réellement engagé**, pas un montant
  provisoire. Le sizing part de là :
  - budget de risque à 0,75 % = **15 CAD** par transaction ;
  - plancher NAV-proportionnel (D13) à 0,5 % = **10 CAD** de risque ;
  - le plafond de notional à 5x NAV vaut 10 000 CAD, ce qui laisse de la marge pour des positions
    légitimes sur GBP_JPY et EUR_USD, mais pas pour une erreur de sizing.
  Conséquence : le compte live ne peut pas absorber la taille qu'une stratégie produirait en paper sans
  re-calcul. Toute promotion (D7, D14) doit donc **recalculer la taille sur la NAV du compte de
  destination**, et jamais transporter les unités de l'environnement précédent. Un simple copier-coller
  de paramètres d'un compte de 95 800 CAD vers un compte de 2 000 CAD produirait une position 48 fois
  trop grosse.

## 4ter. Correction : les tokens OANDA sont par utilisateur, pas par compte

Constat mesuré le 2026-09-30, qui corrige une hypothèse de la section 5 :

| Token | sha256 (8) | Comptes ouverts |
|---|---|---|
| ancien | `dfc03530acdf` | 8, dont `-012`, **sans** `-013` ni `-014` |
| nouveau | `eb1e7e7b172e` | **13**, dont `-013` et `-014` |

Le nouveau token ouvre les treize sous-comptes, y compris `-004`, `-011` et `-012`. Il n'existe
donc pas de token « propre à un environnement » au sens strict : OANDA rattache un token à
l'utilisateur, et un token créé **avant** la naissance d'un compte ne le voit pas. C'était la vraie
cause du 403, pas un défaut de périmètre.

**Conséquence directe sur l'invariant** : la séparation entre dev, paper et live ne peut pas reposer
sur l'identifiant seul, puisque le même token ouvre les trois. Elle repose donc sur :

1. `OANDA_ACCOUNT_ID` comme frontière réelle (un processus dev ne peut toucher que `-013`) ;
2. une **garde de démarrage qui refuse un couple environnement / compte incohérent**, en comparant
   l'identifiant configuré à une table de correspondance versionnée ;
3. l'**hôte d'API** comme second contrôle (practice contre fxtrade), ce qui empêche un `ENV=paper`
   de viser le compte réel même avec le bon numéro de compte.

Sans le point 2, le même token dans les trois fichiers rendrait la séparation purement déclarative.
C'est le seul endroit de ce PRD où une mesure contredit une décision antérieure, et c'est corrigé ici
plutôt que laissé passer.

- **D16** — Le trader GBP_JPY **migre vers `-014`** (paper). Conséquence assumée et explicite : la
  position `2082` (SELL 92 600 GBP_JPY, entry 208,641, SL 209,320, TP 207,976) doit être **fermée**,
  ce qui réalise la perte en cours (~188 CAD au moment du choix), et le sizing est **recalculé sur
  2 000 CAD** au lieu de 95 600.
- **D17** — Les trois stratégies LT migrent vers `-014` et y redémarrent **propres, sans historique de
  position**. Aucune position n'est transplantée d'un compte à l'autre : un état qui décrit une
  position inexistante sur le compte cible est précisément le genre d'incohérence qui produit un ordre
  fantôme au premier cycle.
- **D18** — `-013` (dev) **reste vide pour l'instant**. Son rôle est d'exister pour valider la
  plomberie au moment où le besoin apparaît, pas d'héberger du travail en attente. Un environnement
  vide et fonctionnel vaut mieux qu'un environnement rempli pour justifier son existence.
- **D19** — `-012` (95 600 CAD, alias « trading bridge 2k ») **devient dormant** : plus rien n'y trade
  une fois la migration faite. Il n'est ni supprimé ni recyclé dans la foulée, parce que c'est le seul
  endroit où l'historique réel du trader existe encore.

### Vérification de viabilité du sizing sur 2 000 CAD

Question légitime avant de fermer une position pour migrer : est-ce qu'un budget de 15 CAD produit
encore une taille tradable ? Calcul sur la position réelle, avec la distance de stop de `2082` :

| Étape | Valeur |
|---|---|
| Budget de risque (0,75 % de 2 000) | 15 CAD |
| Distance de stop de 2082 | 0,679 JPY par unité |
| Conversion JPY vers CAD (CAD/JPY ≈ 116) | 0,00585 CAD de risque par unité |
| **Unités pour 15 CAD** | **≈ 2 560 unités** |
| Plancher pratique d'un micro-lot | 1 000 unités |

La taille reste donc **au-dessus du plancher d'un micro-lot**, avec une marge d'environ 2,5x. La
migration est viable. Les taux utilisés sont approximatifs (GBP/CAD ≈ 1,80), donc le chiffre à retenir
est l'ordre de grandeur, pas la décimale : le sizing tient sur 2 000 CAD, il ne produit pas de position
microscopique.

- **D20** — Martin ne sait pas qui envoie les ordres EUR_USD horaires sur `-012`. **Enquête demandée
  explicitement.** Ce n'est plus une anomalie documentée mais une question ouverte avec un propriétaire :
  quelque chose trade un compte dont personne n'assume les ordres, depuis juin 2026.
- **D21** — Le P&L par transaction doit être **converti en devise du compte (CAD)** et non seulement
  étiqueté, dans les deux sens (positif comme négatif). Un chiffre en JPY face à un résumé en CAD reste
  faux même correctement étiqueté. L'étiquette seule (déjà poussée) est un état intermédiaire.
- **D22** — Ordre des opérations : **migrer d'abord** vers `-014`, **restructurer le compose ensuite**,
  autour de la réalité constatée. La migration est donc la première utilisation de la structure cible,
  pas une conséquence de sa construction.
- **D23** — La baisse de `-012` au profit de `-014` (D16, D19) signifie que le compte qui reçoit les
  ordres mystérieux va devenir **dormant et donc isolé** : après migration, si des ordres horaires
  continuent d'y apparaître, leur source sera identifiable sans le bruit du bridge. C'est un argument
  supplémentaire pour migrer d'abord.

- **D24** — **Plafond de risque global par compte**, au lieu de budgets indépendants par stratégie :
  trois pour cent de la NAV en risque simultané (60 CAD sur 2 000), réparti entre les stratégies, avec
  **refus d'entrée** quand le plafond est atteint. Sans cette règle, cinq stratégies à 15 CAD chacune
  peuvent porter 75 CAD de risque en même temps sur un compte de 2 000 CAD, soit 3,75 %, et personne ne
  verrait le total avant l'ouverture simultanée de cinq positions perdantes. C'est la première fois que
  la taille d'une position dépend de l'état d'une AUTRE stratégie.

  **Ce que ça impose à l'architecture, et c'est non trivial :** les stratégies tournent dans des
  processus séparés, donc elles ne partagent aucune mémoire. Un plafond commun exige un **registre de
  risque au niveau du compte**, consulté avant chaque entrée et mis à jour à chaque ouverture et
  fermeture. Deux difficultés à traiter explicitement en Phase 3 :
  - **atomicité** : deux stratégies qui entrent au même instant doivent voir la même valeur de risque
    disponible, sinon le plafond est dépassé par une course ;
  - **cohérence** : en cas de redémarrage, ce registre doit pouvoir se reconstruire depuis le courtier
    (positions et ordres réels) et non depuis un fichier local, sinon un état perdu relâche le plafond
    silencieusement. C'est le même piège que la perte d'état à chaque recréation (D9), appliqué à
    l'échelle du compte.

## 4quater. Rotation des clés exposées : offerte, déclinée (2026-10-01)

Trois jetons ont transité par la messagerie pendant la session : la clé live (`9250a361…`, compte réel
`001-002-1889378-005`, 2 000 CAD), l'ancienne clé practice `-012`, et une nouvelle clé ouvrant les
13 sous-comptes. L'ancienne `-012` renvoie 401 : elle est morte, ce qui est le résultat attendu.

La rotation des deux autres a été proposée avec la méthode propre (régénérer dans l'interface OANDA et
déposer la nouvelle valeur directement dans les fichiers `.env*` sur la machine, sans repasser par un
message). **Martin a décliné : « ce n'est pas un risque pour moi, on laisse tomber. »** C'est son
compte, sa décision, et elle est enregistrée ici pour qu'un lecteur futur sache que l'exposition était
connue et acceptée, plutôt que de la découvrir et de croire à un oubli.

Conséquence pratique à garder en tête : les fichiers `.env.live`, `.env.dev` et `.env.paper` portent des
jetons qui ont circulé dans un historique de conversation. Ils sont en mode 600 et ignorés par git,
donc ils ne fuient pas par le dépôt. La seule exposition restante est l'historique de session.

- **D25** — Le sélecteur dev / paper / live du dashboard est **en lecture seule**. Il change ce qui est
  affiché, jamais ce qui est agi : aucun bouton du sélecteur ne peut ouvrir, modifier ou fermer quoi que
  ce soit sur un compte, y compris en paper. La lecture seule n'est pas une timidité de première version,
  c'est la bonne frontière : le tableau de bord sert à **voir** l'état, et le pilotage reste dans le
  runner, qui a les gardes, les tests et les limites de risque. Une page web qui peut trader est une
  surface d'attaque déguisée en commodité, et elle contournerait d'un clic les plafonds de D24.

  Nuance assumée : le bouton de fermeture d'urgence qui existe déjà (décision du 2026-09-30, gardé
  volontairement) reste en place. C'est une exception délibérée et documentée, pas une incohérence : il
  ne fait que **réduire** une exposition, jamais l'augmenter.

## 4quinquies. Clôture de la position orpheline de `-012` (2026-10-01)

Constat fait après la migration, en vérifiant l'état réel du compte dormant : `-012` n'était **pas à
plat**. Il y restait une position ouverte que plus aucun processus ne gérait.

| Champ | Valeur |
|---|---|
| Instrument | EUR_USD |
| Unités | −1 000 (short) |
| Ouverture | 2026-09-30T14:07:41Z (10:07 locale) |
| Entrée | 1,13623 |
| Brackets laissés chez OANDA | SL 1,13668 / TP 1,12524 |
| Géré par | personne — les quatre conteneurs tradent `-014`, qui est à plat |

Elle vient de la migration, pas d'une stratégie : les stratégies LT ont redémarré propres sur `-014`
(D17) sans transplanter leur état, et la position qu'elles détenaient sur `-012` est restée derrière.
Un état qui décrit une position inexistante est le danger identifié par D17 ; une position qui survit
à son état est l'autre moitié du même problème, et elle était passée inaperçue.

- **D26** — La position est **fermée** (trade `2078`, fermeture au marché à 1,13230, `pl = +5,54 CAD`),
  décision explicite de Martin. `-012` est désormais **réellement à plat** : 0 position, 0 ordre,
  0 trade, solde 95 605,93 CAD. La raison n'est pas la position elle-même, qui était minuscule, mais le
  test d'isolation de D23 : à partir de maintenant, **tout ordre apparaissant sur `-012` ne peut venir
  que de la source inconnue de D20**, puisqu'aucun processus connu n'y a plus accès.

### Indice neuf sur D20

Sur la fenêtre du 17 au 30 septembre, `-012` a reçu **31 ordres EUR_USD** : 2 portent l'étiquette
`ltrsi3_EURUSD`, **21 un tag UUID anonyme**, et 8 aucun tag. Le code du bridge n'accepte un tag nommé
que si l'appelant le fournit (`placeMarketOrder(instrument, units, clientTag)`, `HttpOandaRestClient`) :
les tags UUID viennent donc d'un **chemin de code différent** de celui qui écrit un nom de stratégie.
C'est la première piste vérifiable de D20, et elle est statique — elle ne demande aucune requête sur un
compte.

## 4sexies. D20 résolu : la source inconnue est le plan de contrôle en dev (2026-10-01)

**Verdict : les ordres EUR_USD anonymes de `-012` ne viennent d'aucun tiers et d'aucune stratégie
vivante. Ils viennent de `active-run-123`**, un run dev/test du plan de contrôle
(`ControlPlaneMain`, `trading-runtime`) portant la stratégie `LondonOpenRangeBreakout` sur `EUR_USD`
avec l'étiquette d'exécution `PAPER_OANDA`, persisté dans `data/runtime/events.db`.

Le mécanisme, tel que les journaux le montrent :

```
Restoring active run active-run-123 (LondonOpenRangeBreakout on EUR_USD)...
BrokerRunExecutor - Restored open position SELL 1000.0 @ 1.13623 for run active-run-123 (restart adoption)
```

À chaque démarrage du plan de contrôle avec des identifiants OANDA présents dans l'environnement, le
run persisté est restauré, la position est adoptée chez le courtier, et des ordres au marché réels
(practice) sont envoyés **sur le compte que l'environnement désigne**. C'est exactement le défaut que
D23 décrivait : quelque chose trade un compte dont personne n'assume les ordres.

**Ce qui ferme l'enquête, pièce par pièce :**

| Observation | Ce qu'elle prouve |
|---|---|
| 17 restaurations dans le journal du 2026-09-30, plus le 2026-08-24 et le 2026-09-29 | le déclencheur est un démarrage du plan de contrôle, pas un calendrier |
| `Restored open position SELL 1000.0 @ 1.13623` à 12:59 et 14:19 | c'est **la** position fermée par D26 (trade `2078`, entrée 1,13623) |
| Tags UUID sur les ordres d'ouverture, aucun tag sur les fermetures | `OandaBroker` étiquette avec `order.id()` (UUID) ; `LiveStrategyRunner`, lui, écrit `strategie_SYMBOL` depuis le 2026-05-27 — d'où l'impression d'une source étrangère |
| Aucun cron Hermes ne lance `mvn` ni `ControlPlaneMain` | le déclencheur est un travail humain ou d'agent, pas un calendrier |
| Rafales de 3 ouvertures puis fermetures (06:37, 06:59, 07:38, 08:15, 09:14, 09:39, 09:56) | profil d'une exécution de test, pas d'une stratégie |
| Les exécutions du 2026-09-30 coïncident avec une session de l'agent Antigravity travaillant sur `BrokerRunExecutor.restoreOpenPosition` et `ControlPlaneServerTest` (fin 11:26) | l'opérateur est un agent qui développait précisément le chemin de restauration — donc quelqu'un qui lançait la suite de tests |

Ce que ça change dans la lecture du risque : **un agent qui travaille sur le code de restauration
place des ordres réels en lançant les tests.** La suite n'offre aucune barrière entre « je compile et je
teste » et « j'envoie un ordre au courtier » : il suffit que des identifiants soient présents dans
l'environnement.

- **D27** — **D20 est fermé** : la source est identifiée, elle est interne, et elle est nommée dans ce
  document pour qu'elle ne soit plus jamais un mystère. Deux conséquences à traiter en Phase 3 :
  - **Le run persisté est un danger pour `-014`.** Depuis la migration, l'environnement du plan de
    contrôle désigne `-014`, donc le prochain démarrage en dev y enverra des ordres — sur le compte que
    les quatre stratégies tradent. Une exécution de test peut donc injecter une position fantôme dans
    un compte suivi, ce qui est plus grave qu'avant puisque ce compte porte désormais des stratégies
    réelles.
  - **Un run dev ne doit pas pouvoir utiliser des identifiants réels.** Les tests concernés ne
    s'exécutent (`assumeTrue`) qu'en l'absence d'identifiants dans l'environnement : la suite est donc
    conçue pour frapper le courtier dès que des identifiants sont présents. C'est la même famille de
    défaut que la garde de démarrage de la section 5, appliquée aux runs de développement.

### 4septies. D28–D31 — deux défauts du chemin live, vérifiés le 2026-10-01

Trouvés en auditant le backtest contre le courtier (fee/swaps + backtest vs paper), pas en cherchant des
bugs. Les deux sont des écarts entre ce que la configuration **dit** et ce que le runner **fait** : voir
la classe de défaut « doc vs code » ajoutée à `docs/TRADING-GUARDRAILS.md`.

- **D28** — L'instrument tradé doit venir d'une **source explicite**, jamais du **nom d'affichage** de
  la stratégie. Vérifié : `LiveStrategyRunner.toOandaSymbol()` (`LiveStrategyRunner.java:1616-1646`)
  résout par `name.contains(...)` et retombe sur `GBP_JPY`. `VWPReversionStrategy` porte le nom
  `"🔁 VWAP Reversion"` (`VWPReversionStrategy.java:33`) que la table ne reconnaît pas (`VWPREVERSION`),
  donc `vwpreversion` tradait **GBP_JPY** pendant que sa config annonçait **USD_CHF**. Corollaire
  mesuré : `STRATEGY_PAIR` du compose est **inerte** pour ce runner.
- **D29** — Le mécanisme est corrigé **avant** de trancher la paire : `vwpreversion` sera re-backtestée
  sur USD_CHF et GBP_JPY, et la paire retenue le sera **sur preuve**. Aucun choix par défaut.
- **D30** — `ltrsi3` (`LtRSI3Momentum.evaluateEntry()`) émet ses entrées **sans stop** : le stop ATR et
  la cible sont des champs internes, jamais attachés à l'ordre. Décision : le **stop ATR part sur
  l'ordre** (il vit chez le courtier), la **cible reste gérée par la stratégie**, et une **garde du
  moteur refuse toute entrée sans stop** (elle est aussi le dénominateur du budget de risque, sans quoi
  la taille envoyée n'est pas celle de la policy).
- **D31** — Une **seconde porte** est écrite pour les edges mono-instrument : OOS PF stable, DD ≤ 10 %,
  faible nombre de trades admis lorsque le mécanisme est calendaire ou de session, la période paper de
  30 jours fournissant l'échantillon manquant. La porte actuelle (PF ≥ 1,2 sur 2 paires sur 3, ≥ 30
  trades) bloque structurellement 5 candidats validés depuis six semaines.

Tech-spec, user stories, coding stories et critères d'acceptation :
`_bmad-output/planning-artifacts/tech-spec-live-path-instrument-and-stop.md`.

## 5. Invariant de sécurité non négociable

**Le runner doit REFUSER de démarrer si l'environnement déclaré et les identifiants ne concordent
pas.** Exemple : `ENV=live` accompagné d'un token practice, ou `ENV=paper` accompagné d'un token
réel. Sans cette garde, la séparation est décorative : une variable mal réglée suffit à envoyer un
ordre de test sur le compte réel. C'est l'invariant central de cette refonte, et il doit être vérifié
par un test, pas par une relecture.

Corollaire, tiré du défaut trouvé aujourd'hui : **un identifiant ne doit pas être partagé entre deux
environnements**. Le token `-012` est actuellement partagé entre le bridge et les outils Hermes, ce
qui est exactement la faute que cette refonte doit rendre impossible.

## 6. Stories utilisateur (valeur)

| # | En tant que... | Je veux... | Pourquoi |
|---|---|---|---|
| US-1 | Martin, propriétaire du compte | pouvoir essayer une stratégie sans risquer un dollar réel | le 494 CAD perdu en 7 s l'a été sur le compte de production, faute d'endroit où essayer |
| US-2 | Martin | que le compte réel ne puisse pas être atteint par une erreur de configuration | une variable mal réglée ne doit jamais suffire à trader de l'argent réel |
| US-3 | Martin | voir d'un coup d'œil quel environnement tourne quel code | trois conteneurs ont dérivé deux mois sans que rien ne le signale |
| US-4 | Martin | promouvoir une stratégie de dev vers paper puis live par une décision explicite | une stratégie ne doit pas se retrouver en réel par défaut |
| US-5 | Martin | qu'un redéploiement ne perde plus l'état d'une position ouverte | la position GBP_JPY a failli être perdue à chaque déploiement |

## 7. Coding stories (à découper après les réponses)

Squelette, à figer en Phase 3 :

| # | Story | Effort | Dépend de |
|---|---|---|---|
| 1 | Sortir l'état de `/tmp` vers un volume monté | S | US-5 |
| 2 | Garde d'environnement dans le runner (refus si flag et identifiants divergent) + test | M | US-2 |
| 3 | Un seul `build:` partagé, services multiples par `image:` | S | US-3 |
| 4 | Définir les 3 services (dev, paper, live) avec leurs comptes | M | US-1, US-4 |
| 5 | Service `live` défini mais non démarré, avec procédure de promotion | S | US-4 |
| 6 | Vérification d'environnement affichée au démarrage et dans le monitoring | S | US-3 |

## 8. Questions ouvertes (à poser une à la fois)

- **Q2** — Qui fournit les deux comptes manquants (practice jetable pour dev, réel pour live) et
  quand ? L'architecture peut être bâtie sans, mais `live` ne peut pas démarrer.
- **Q3** — Promotion d'une stratégie : décision manuelle de Martin, ou règle automatique sur des
  critères mesurés (durée, P&L, nombre de trades) ?
- **Q4** — Que devient `nfp-week`, défini dans le compose et jamais démarré : environnement, ou
  suppression ?
- **Q5** — Les 3 services actuels (`lt-rsi3`, `comp-momentum`, `month-week`) : paper ou dev ?
- **Q6** — L'état doit-il être partagé entre environnements (non) ou un volume par environnement ?

## 9. Hors périmètre

- Changer les stratégies elles-mêmes ou leurs paramètres de risque.
- Le nettoyage de `events.db` (fait le 2026-09-30, avec prune quotidienne dans le conteneur).
- La bascule vers un courtier autre qu'OANDA.

## 10. Critères de succès

1. Un `docker compose build` ne produit **qu'une seule image** pour tous les environnements.
2. Le runner **refuse de démarrer** sur incohérence environnement / identifiants, prouvé par un test.
3. Un redéploiement ne perd **aucune position ouverte**, sans intervention manuelle.
4. Aucun identifiant OANDA partagé entre deux environnements.
5. `git rev-list --count origin/master..master` vaut 0 après chaque étape.
