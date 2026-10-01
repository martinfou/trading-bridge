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
