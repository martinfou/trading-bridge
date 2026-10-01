# 🛡️ Trading Guardrails — politique d'auto-protection

> **Principe** : Martin ne fait pas la revue du code de trading. Le système doit donc se protéger
> tout seul : invariants dans le code, vérification à l'exécution, limites de risque, porte de
> déploiement automatique, et des chiffres qui viennent **toujours** du courtier.
>
> Dernière mise à jour : 2026-09-29 (créé après l'incident P&L du 29 septembre).

## L'incident qui a créé ce document

Le compteur de P&L interne du runner affichait **+350,50 $** pour la stratégie `consecbar` et
**−116,08 $** pour `vwpreversion`, alors que le compte OANDA avait réalisé environ **±1 $ CAD** sur
les mêmes transactions GBP_JPY.

Cause racine : le chemin de sortie sur signal de la stratégie ajoutait une estimation calculée
dans la **devise de cotation** de l'instrument (`Δprix × unités` = **JPY** pour GBP_JPY) au même
accumulateur qui recevait le `realizedPL` du courtier, **en devise du compte**. Trois défauts :
surdéclaration ~×108, aucune correction ultérieure (la transaction était retirée du suivi avant
réconciliation), et **double comptage** quand la réconciliation asynchrone complétait la même
transaction. La valeur contaminée était persistée puis rechargée à chaque redémarrage.

## Les cinq couches

| # | Couche | Ce qu'elle empêche | État |
|---|---|---|---|
| 1 | **Invariants du code** | La classe de bug elle-même, par construction | en place (revu, testé) |
| 2 | **Vérification à l'exécution** | Une dérive silencieuse entre le système et le courtier | en place (revu, testé) |
| 3 | **Limites de risque** | Une perte qui dépasse ce qui a été décidé | **partiellement prouvée** — l'instrument tradé et le stop sur l'ordre le sont (commit `20b28c86`, voir ci-dessous) ; le **plafond de compte (D24) reste NON implémenté** |
| 4 | **Porte de déploiement** | Du code rouge qui part en production | en place (testée) |
| 5 | **Rapports courtier** | Un chiffre inventé présenté comme la vérité | en place |

> ⚠️ **Correction du 2026-09-30.** La première version de ce document présentait les cinq couches
> comme « en place ». C'était faux pour la couche 3 : la **limite de perte journalière (−2 % de la
> NAV)** et le **plafond d'une position par stratégie et par instrument** sont décrits ici comme
> politique cible, mais **rien ne prouve encore qu'ils existent dans le code**. Un document de
> garde-fous qui affirme plus que le code ne fait est lui-même un risque : toute ligne de ce fichier
> est une **promesse à vérifier**, pas un état constaté. La vérification « documenté vs implémenté »
> fait partie du mode architecture de la revue indépendante (§ Journal des revues).

### Couche 1 — Invariants du code (par construction)

1. **Un seul écrivain de P&L.** Le total réalisé n'est alimenté que par la réconciliation avec le
   courtier (`RealizedPnlLedger.recordBrokerPnl`). Aucun autre chemin ne peut le modifier.
2. **Une transaction = une entrée.** L'entrée est indexée par le `tradeId` du courtier : une
   réconciliation répétée est détectée, journalisée et **ignorée** (pas de double comptage).
3. **Une estimation n'entre jamais dans le total.** Les estimations locales sont enregistrées comme
   *ignorées* et restent visibles dans les compteurs d'observabilité uniquement.
4. **Devise explicite.** Le total est en devise du compte (CAD), tel que fourni par OANDA.
5. **Aucune valeur historique contaminée n'est reprise.** Les états persistés antérieurs à la
   version d'écriture courante sont **abandonnés** au redémarrage, jamais transportés.

### Couche 2 — Vérification à l'exécution

- Toutes les **30 minutes**, le runner compare **chaque transaction du registre** avec le
  `realizedPL` d'OANDA pour le même `tradeId`.
- Tolérance de divergence : **max(0,02 $ CAD, 1 %)**. Au-delà : `ERROR` dans le journal, compteur
  `pnlIntegrityMismatches` incrémenté et écrit dans l'état/moniteur.
- Si le courtier est injoignable, la vérification est sautée silencieusement (pas de faux positif),
  et une réconciliation en attente **bloque les nouvelles entrées**.
- Repli sans courtier : le P&L de la transaction reste **inconnu** (jamais faux) et est compté dans
  `ignoredLocalEstimates`.

### Couche 3 — Limites de risque

- **Taille** : plafonnée par stratégie (`live-config.json`, défaut 1,5 % du solde, 0,5 % pour une
  stratégie inconnue du config).
- **Perte journalière** : au-delà de **−2 % de la NAV**, plus aucune nouvelle entrée de la journée
  (les sorties continuent).
- **Une position ouverte par stratégie et par instrument** ; pas d'augmentation de levier sans
  changement explicite de configuration.
- **Compte** : `paper`/`practice` uniquement. Aucun ordre sur un compte réel sans décision
  explicite de Martin, écrit dans ce document.
- **L'instrument tradé est celui de la config, ou le runner refuse de démarrer.** Vérification du
  2026-10-01 : `LiveStrategyRunner.toOandaSymbol()` (`LiveStrategyRunner.java:1616-1646`) résolvait
  l'instrument depuis le **nom d'affichage** de la stratégie et retombait sur `GBP_JPY`. Le nom de
  `VWPReversionStrategy` est `"🔁 VWAP Reversion"` (`VWPReversionStrategy.java:33`) mais la table
  teste `"VWPREVERSION"` : pas de correspondance, donc défaut. Résultat observé : `vwpreversion`
  tradait **GBP_JPY** alors que `config/live-config.json` annonce **USD_CHF**. Corollaire : la
  promesse « taille plafonnée selon `live-config.json` » ci-dessus était **fausse en fait**, puisque
  la config ne décrivait pas l'instrument réellement tradé. `STRATEGY_PAIR` était par ailleurs une
  variable morte pour ce runner.
- **Aucune entrée ne part sans stop.** Vérification du 2026-10-01 : `LtRSI3Momentum.evaluateEntry()`
  émet `new Order(symbol, Side.BUY, MARKET, units, entryPrice)` **sans** `withStopLoss` /
  `withTakeProfit` ; le stop ATR et la cible existent seulement comme champs internes. Conséquences :
  aucun stop chez le courtier, et le budget de risque (`riskSizedUnits`, qui divise par la distance au
  stop) n'a **pas de dénominateur**, donc la taille envoyée n'est pas celle que la policy annonce.
  Seule la vérification in-process protège la position.

> **Classe de défaut nommée le 2026-10-01 : « doc vs code ».** Ces deux-là n'étaient pas des bugs de
> calcul, mais des écarts entre ce que la configuration et ce document **disent** et ce que le runner
> **fait**. Ce type d'écart est invisible en test unitaire (chaque côté est cohérent avec lui-même) et
> n'apparaît qu'en comparant les deux, ou en lisant un log de démarrage. La couche 2 doit donc
> vérifier non seulement le P&L contre le courtier, mais aussi **l'instrument et la présence du stop**
> contre la configuration, et **au démarrage**, pas à la première transaction.

**État au 2026-10-01, après déploiement (18:31 EDT).**

- L'instrument est résolu depuis la config (`strategies.<clé>.instrument`), le champ `symbol` de la classe
  est réconcilié (sinon `ltrsi3` filtrait 100 % de ses barres, panne muette), et un couple incohérent
  **refuse le démarrage** en nommant la stratégie et la paire attendue. Commit `8cb3e909`, 13 tests dédiés.
  **Déployé, et constaté dans le journal de démarrage** :
  `🔁 VWAP Reversion (instrument: USD_CHF [source: config strategies.vwpreversion.instrument])`.
- Le stop part **sur l'ordre d'entrée** (`stopLossOnFill`, plus de second appel après le fill) et une
  garde **unique** refuse toute entrée sans stop depuis le point de dispatch qui couvre MARKET **et** STOP.
  Commit `20b28c86`, 82 tests, et la classe est présente dans l'artefact déployé.
- **Déployé le 2026-10-01** (commit `d4329edf`) sur les quatre services. Fenêtre d'observation ouverte et
  consignée dans `docs/paper-window-log.md` : NAV 1981.3922 CAD, `lastTransactionID` 218.
- **La porte de déploiement est verte** depuis que `RunManagerTest` est hermétique. Le test de concurrence
  ne compte plus des retours : il mesure la concurrence **dans** la section critique et **échoue 3 fois
  sur 3** quand on neutralise le verrou (preuve de non-vacuité rejouée par l'orchestrateur). La classe ne
  touche plus le courtier : 0 appel.

#### Couche 1, invariant 6 : le fil-piège des ordres

Un ordre ne quitte la JVM que si `TB_ALLOW_ORDERS` vaut exactement `1`/`true`, et **jamais** depuis un
runtime de test. Sept points d'appel couvrent les trois chemins d'ordre (`OandaExecutor` x4,
`HttpOandaRestClient` x2, `TcpIbkrGatewayClient` x1).

Ce qui l'a rendu nécessaire : **la suite de tests a envoyé 12 ordres réels** sur le compte paper le
2026-10-01 (12 `MARKET_ORDER` EUR_USD `units=-1000`, tickets 195 à 217), tous annulés avec
`STOP_LOSS_ON_FILL_LOSS`, **aucun fill**. Le compte n'a pas été touché **par chance** : le stop attaché
venait de 10 barres synthétiques, donc OANDA l'a rejeté. Avec un stop plausible, ces 12 ordres se
remplissaient. Le mode `BACKTEST` n'a pas empêché l'envoi : **le mode n'est pas une frontière de
sécurité**, l'endpoint et la credential le sont.

Détail qui a failli coûter une panne : la détection du runtime de test ne doit **jamais** inclure `junit`.
L'image de production contient 6 jars JUnit sur 47 (`junit-jupiter-*`, `junit-platform-*`) et zéro
`surefire`/`failsafe`/`testng`/`idea_rt`/`gradle`. Ajouter `junit` comme jeton ferait refuser **tous** les
ordres de **tous** les conteneurs, silencieusement. Un test encode cette mesure pour que la panne ne
puisse pas être réintroduite.

#### Résidus assumés (2026-10-01)

- **Plafond de risque par compte (D24, 3 % de NAV simultanée)** : toujours pas implémenté. La somme des
  `computedRiskPct` déployés est **2,3 %**, sous le plafond, et doit le rester à chaque édition de
  `live-config.json`.
- **Retry d'ordre** : le client OANDA ne réessaie que les échecs de connexion, donc une fermeture du
  serveur **pendant** l'envoi n'est pas réessayée. Or réessayer un POST d'ordre peut doubler une position :
  c'est une décision de trading, pas de code, et elle attend sa propre conversation.

### Couche 4 — Porte de déploiement

`scripts/pre-deploy-gate.sh` : build + suite de tests complète, validation de `docker-compose`,
et **smoke test OANDA en lecture seule**. Le déploiement (`scripts/deploy-paper.sh`) refuse de partir si
la porte échoue, et il lance la porte lui-même. Une **revue indépendante** est exigée avant le merge dans
la branche par défaut, jamais Martin :

| Type de diff | Relecteur |
|---|---|
| Chemin d'ordre, chemin live, dimensionnement du risque, tout ce qui touche l'argent réel | **Elliot** (persona développeur senior) **et** la porte `agy` à deux passes |
| Tout le reste (docs, tests, outillage, recherche) | la porte `agy` à deux passes |

Deux faits à connaître sur cette porte, appris le 2026-10-01 :

1. **Elle validait les mauvaises credentials.** Le smoke test lisait `~/.hermes/.env` (clé et compte
   `-012`, dormant) alors que le déploiement paper utilise `.env.paper` (compte `-014`). Une porte verte ne
   prouvait donc rien sur la credential réellement déployée, et la clé périmée la rendait rouge en
   permanence. Une porte qui échoue pour une mauvaise raison finit contournée, et une porte qui passe pour
   une mauvaise raison est pire encore. Corrigé : `$TB_ENV_FILE`, puis `.env.paper`, puis
   `~/.hermes/.env` avec avertissement, et le compte vérifié est toujours imprimé.
2. **Un constat de relecture est une hypothèse, jamais un verdict.** Le même jour, trois passes ont
   affirmé qu'un argument de fin n'était pas celui qu'il est (faux : la ligne était hors du hunk du diff
   fourni), et une passe a recommandé d'ajouter `junit` aux jetons de détection, ce qui aurait arrêté tous
   les conteneurs. Avant d'appliquer un constat qui touche la production : **mesurer le système**.

### Les quatre voies (ajouté le 2026-10-01)

Une seule voie touche quelque chose qui compte, et elle a un nom, un compte et un journal.

| Voie | Compte | Ce qu'on y fait | Règle |
|---|---|---|---|
| **Recherche** | aucun | backtests, modèle de coûts, walk-forward, shortlist | aucun courtier, jamais |
| **Dev** | `-013` (pristine, jamais tradé) | chemins d'ordre, retry, candidat de bout en bout | `scripts/dev-lane.sh`, état dans `./data-dev`, refus de démarrer si le compte dev = le compte paper |
| **Paper** | `-014`, **la fenêtre** | observer un candidat validé pendant 30 jours | réservé : aucune exploration, baseline consignée dans `docs/paper-window-log.md` |
| **Live** | `-005` (2 k$) | argent réel | aucun ordre sans décision écrite de Martin dans ce document |

Deux détails qui ont failli coûter cher :

1. **Le token est partagé entre `-013` et `-014`.** Un seul `OANDA_API_KEY` ouvre les deux comptes :
   l'isolation dev/paper tient donc à une variable d'environnement, pas à une credential. Une
   isolation réelle demande un token par compte (à créer dans l'interface OANDA ; ce n'est pas
   automatisable ici). En attendant, `scripts/dev-lane.sh` refuse de démarrer si les deux comptes
   sont identiques.
2. **La voie dev a ses propres répertoires d'état** (`./data-dev`, `./logs-dev`) parce que
   `docker-compose.yml` monte les mêmes `./data` et `./logs` dans les conteneurs de la fenêtre.
   Un conteneur dev qui écrirait là-dedans contaminerait l'état du runner qui produit
   l'observation.

#### Mode test : plus aucune credential vivante, jamais

Le 2026-10-01, la suite de tests a envoyé 12 ordres réels. Le mécanisme n'était pas une garde
manquante : `trading.bridge.test` était bien posé, et `loadDefault()` renvoyait bien le compte
synthétique, mais ce compte portait `token`/`accountId` **nuls** en nommant les variables
d'environnement, donc `credentials()` lisait les valeurs réelles. Un `.env.paper` exporté dans le
shell du développeur était hérité par la JVM de test, et l'état de terminal persiste d'un appel à
l'autre : la variable est restée exportée des heures.

`credentials()` renvoie maintenant des sentinelles codées en dur en mode test, pour **tous** les
comptes, quoi que dise l'environnement. Le correctif ne dépend pas de la discipline du shell, ce
qui est précisément ce qui le rend fiable.

### Couche 5 — Rapports courtier

- Le **courtier est la seule source** des chiffres de P&L (positions, transactions, `realizedPL`).
- Les compteurs internes sont de l'**observabilité**, jamais une vérité comptable.
- Toute divergence, tout repli de réconciliation et toute limite atteinte sont **notifiés en une
  ligne** à Martin : visibilité, sans demande d'action.

## Ce que ce document n'est pas

- Pas une garantie de performance : il protège la **vérité comptable** et le **risque décidé**, pas
  contre une stratégie qui perd de l'argent par conception.
- Pas une autorisation d'augmenter le risque : toute hausse de taille, de levier ou de périmètre
  passe par une modification explicite de ce fichier.

## Journal des revues indépendantes

### 2026-09-29 — commit `62500873` (revue par un second agent, lecture seule)

Verdict : **aucun BLOCKER**. L'invariant comptable tient (devise du compte, source courtier, une
fois par transaction). La revue a néanmoins trouvé **2 MAJEURS, 4 MINEURS, 4 NITs** — dont un bug
introduit par moi au commit `2777bda8`, que mon propre résumé ne mentionnait pas.

| # | Sévérité | Constat | État |
|---|---|---|---|
| M1 | majeur | Le watchdog tournait **sur le thread de trading** et appelait le courtier une fois par entrée, sans borne → blocages du loop et ~175 000 appels/jour après un an | corrigé (thread worker, fenêtre bornée à 200, pacing 50 ms) |
| M2 | majeur | Une transaction CLOSED **sans champ `realizedPL`** était enregistrée **0,00 $** et ne pouvait plus être corrigée (dedupe premier-gagnant) — le watchdog ne pouvait pas le voir, même repli | corrigé (on reporte, on n'enregistre jamais 0 par défaut) |
| M3 | mineur | Après 5 échecs, le repli retirait la transaction **sans jamais enregistrer** son P&L courtier → perdu définitivement et **invisible** du watchdog | corrigé (liste persistée + nouvelle tentative bornée + compteur d'état) |
| M4 | mineur | `totalExits` compté **deux fois** sur les sorties déclenchées par un signal (au signal ET à la réconciliation) | corrigé — **bug de ma part** au commit `2777bda8` |
| M5 | mineur | Registre non borné : une entrée par transaction, **pour toujours**, réécrite dans l'état toutes les 60 s | corrigé (total exact + fenêtre récente de 200 + plafond d'ids) |
| M6 | mineur | État écrit **non atomiquement** et depuis deux threads sans verrou → un état tronqué fait perdre le registre au redémarrage | corrigé (fichier temporaire + `ATOMIC_MOVE` + verrou d'écriture) |
| N1-N3 | nit | Signature trompeuse, compteur non restauré, smoke test qui n'exigeait pas CAD | corrigés |
| N4 | nit | Fenêtre check-then-act étroite entre `hasClosableTrades` et l'enregistrement | laissé tel quel (étroit, préexistant) |

**Leçon à garder** : le résumé de l'agent qui écrit le code n'est pas une preuve. Un second agent a
trouvé un défaut de ressource que le test vert ne montrait pas, et un compteur faux que j'avais
écrit moi-même. La revue indépendante n'est pas une cérémonie — c'est la couche qui remplace la
relecture par Martin.

