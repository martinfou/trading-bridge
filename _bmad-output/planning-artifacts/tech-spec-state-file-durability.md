# Tech-spec — Durabilité du fichier d'état des stratégies live (v2)

- **Date :** 2026-10-26, v2 après Party Mode du même jour
- **Auteur :** Hermes (Phase 2 BMad Method), propriétaire Martin
- **Track :** BMad Method (touche l'argent : une position réelle peut se retrouver sans gestionnaire)
- **Story liée :** `_bmad-output/implementation-artifacts/50-1-etat-persistant-entre-redemoiements.md`
- **Décision :** D42
- **Revue :** Party Mode Winston / Amelia / John, 2026-10-26. Verdicts : CHANGES_REQUIRED (Winston, Amelia), SCOPE_CHANGE (John). Toutes les corrections ci-dessous ont été re-vérifiées dans le code avant intégration.

## 0. Ce que la v1 avait faux

Cette section existe pour que le prochain lecteur ne re-dérive pas les erreurs corrigées.

| Affirmation v1 | Statut | Correction |
|---|---|---|
| « Le défaut est uniquement le chemin `/tmp` » | Partiellement faux | `resumeState()` est all-or-nothing : un `catch (Exception)` global (`:2692`) jette `activeTrades`, `pendingStops`, le ledger et l'état stratégie sur un seul champ corrompu. Un fichier présent mais illisible reproduit le silence de l'incident. |
| « `resumeState()` restaure, donc changer le chemin suffit » | Faux comme garantie | L'entrée marché ajoute à `activeTrades` (`:1483-1489`) **sans** `saveStateNow()`. La persistance arrive au plus tard 60 s plus tard (`saveStatePeriodic`, `:1955`), plus tard encore si `tick` lève. Fenêtre de perte réelle. |
| « La garde d'orphelin filtre par instrument » | Faux et dangereux | `compmomentum` et `monthweekphase` tradent tous deux **USD_JPY** sur le même compte ; `ltrsi3` et `nfpweek` partagent **EUR_USD**. Filtrer par instrument fait lire la position légitime d'une voisine comme un orphelin, et le blocage ne se lève pas tant que ce trade vit. |
| « Le tag `clientExtensions` suffit à filtrer » | Incomplet | Le runner pose `clientExtensions` au niveau **ordre** (`OandaExecutor.java:63-70`), jamais `tradeClientExtensions`. `GET /openTrades` renvoie donc des trades sans extension. Le motif correct existe ailleurs dans le dépôt (`HttpOandaRestClient.java:311`). |
| « `openTrades` est déjà utilisé par `verifyLedgerAgainstBroker` » | Faux | Cette méthode utilise `getTradeDetails(id)` (`:2401`). Les seuls lecteurs de `/openTrades` sont `getOpenTradeIds` (ids seuls, `OandaExecutor.java:246`) et `getOpenPositionUnits` (double agrégé, `:278`). Aucune méthode n'expose `{id, instrument, tag}`. |
| « Les 4 services live » | Faux | Il y en a **5** : `trader`, `nfp-week`, `comp-momentum`, `month-week`, `lt-rsi3`. |
| `TB_STATE_DIR` comme seule mécanique | Deux sources de vérité | `config/live-config.json:16` déclare déjà `"stateDir": "/tmp"`, lu par **aucun** code Java. Consommer ce champ, pas inventer un mécanisme parallèle. |
| Rollback = retirer `TB_STATE_DIR` | Faux | Retirer la variable fait rebasculer en `/tmp`, donc réintroduit le bug au redéploiement suivant. |
| `stateSource: stale-instrument` | État fantôme | Énuméré sans source de vérité. Il devient réel avec la validation d'instrument (§3.3). |
| Story 1.10 (instrument) hors périmètre | À inverser | Elle devient une **précondition** : dès que le fichier survit, il survit aussi à un changement de `STRATEGY_PAIR`. |

## 1. Le défaut, mesuré

`LiveStrategyRunner.java:268` écrit l'état dans `/tmp/live-strategy-state-<stratégie>.json`, et `resumeState()`
(`:2509`, appelé `:1027`) le relit. Dans un conteneur, `/tmp` meurt avec le conteneur, alors que
`docker-compose.yml` ne monte que `./data:/app/data` et `./logs:/app/logs`.

### Incident du 2026-10-26, traçable

| Heure (EDT) | Événement |
|---|---|
| 09:09 | `trading-live` démarre, `No saved state file found — starting fresh` |
| 10:00 | `vwpreversion` ouvre **trade 504** : SELL 4 600 USD_CHF à 0,82689, SL 0,82832, TP 0,82482 |
| 13:09 | Moniteur : `activeTrades: 1`, `open_positions[504]`, `reconciliationStatus: CONFIRMED` |
| 18:00 | `docker compose up -d` recrée `trading-live` pour charger les variables du hub |
| 18:00 | Journal : `No saved state file found — starting fresh` |
| 18:01 | Moniteur : `activeTrades: 0`, `open_positions: []` alors que le courtier détient -4 600 USD_CHF |
| 18:02 | `unreconciled_trades: []` : le système se croit plat, aucune alerte |
| 18:04 | Position fermée à la main, `realizedPL -5,74 CAD`, solde 1 861,15 CAD |

### Quatre défauts dans le même chemin

1. **Support éphémère** : `/tmp` dans un conteneur. Cause de l'incident.
2. **Persistance non synchrone avec l'entrée** (`:1483` vs `:1627`) : jusqu'à 60 s, davantage si le tick lève.
3. **Reprise fragile** : `catch (Exception)` global (`:2692`), boucle `activeTrades` non isolée (`:2577-2597`).
4. **`writeJsonAtomic` sans fallback** (`:2083-2096`) : si le FS refuse `ATOMIC_MOVE`, l'exception remonte
   dans le `WARN` de `saveStateNow` (`:2063`) et l'état n'est jamais écrit, sans alerte.

## 2. Périmètre — deux livraisons

### Lot 1 (cette story) — arrêter la perte silencieuse

| # | Élément | Pourquoi |
|---|---|---|
| L1.1 | Répertoire d'état résolu depuis `live-config.json:16` (champ mort), surchargeable par `TB_STATE_DIR`, injecté **par constructeur** | Cause racine ; couture testable |
| L1.2 | Répertoire pointé sur `/app/data/state` pour les **5** services, `createDirectories` au démarrage, log INFO du chemin | Rend le volume effectif |
| L1.3 | Validation de l'instrument sauvegardé dans `resumeState()` → `stale-instrument`, pas de restauration croisée | Empêche la régression que L1.1 introduit (précondition 1.10) |
| L1.4 | Propagation de `tradeClientExtensions` dans `OandaExecutor` (ordres + stops), sur le modèle de `HttpOandaRestClient:311` | Rend l'attribution fiable |
| L1.5 | `stateSource` (`resumed`/`fresh`/`stale-instrument`/`resume-failed`) + `untrackedBrokerTrades`, **alerte non bloquante**, log ERROR | Le canari |
| L1.6 | `saveStateNow()` immédiat après le fill confirmé | Ferme la fenêtre de 60 s |
| L1.7 | Fallback non atomique dans `writeJsonAtomic` si `ATOMIC_MOVE` est refusé | Un FS exotique ne doit pas rendre l'état non écrivable |

### Lot 2 (story séparée, non ouverte) — blocage des entrées

`ENTRY_BLOCKED_ORPHAN` et le refus d'entrée. **Raison du report** (John, non réfuté) : le blocage défend un
mode de défaillance jamais observé, alors qu'il introduit un risque démontré de blocage croisé sur instrument
partagé. Réexamen quand L1.4 aura produit des trades réellement taggés, avec un test d'attribution sur deux
stratégies USD_JPY.

**Hors périmètre dans les deux lots :** adoption automatique d'un orphelin (D36 / story 1.10), déplacement de
`monitorFile`, changement du format de fichier d'état.

## 3. Pourquoi les stories existantes ne couvrent pas le cas

| Story | Ce qu'elle traite | Pourquoi ça ne suffit pas |
|---|---|---|
| **41.5** | Persister `reconciliationStatus`, réessais, blocage d'entrées | Cite `/tmp/live-strategy-state-*.json` comme support existant, ne remet pas en cause sa durée de vie |
| **16.14** | Trade fermé chez le courtier mais ouvert en local | Sens inverse de l'incident : ici **ouvert chez le courtier, perdu en local** |
| **1.10** | Instrument sauvegardé ≠ instrument résolu | Supposait qu'un fichier existe. Devient une **précondition** de L1.1 |
| **48.2** | Statut de run en base après panne courtier | Concerne `RunManager`, pas le fichier d'état du runner |

## 4. Conception du lot 1

### 4.1 Résolution du répertoire, une seule mécanique

Ordre : `TB_STATE_DIR` (exploitation, tests) > `stateDir` de `config/live-config.json` > `/tmp`.

Le chemin est résolu **hors** du runner et injecté par constructeur
(`LiveStrategyRunner(..., Path stateDir)`), parce que `System.getenv()` n'est pas modifiable in-process : sans
cette couture, le test d'override est intestable. `Files.createDirectories(stateDir)` avant la première
écriture + log INFO du chemin retenu au démarrage : un mauvais montage devient visible dès les premières
lignes du journal.

### 4.2 Détection d'orphelin — alerte seulement

Nouvelle méthode `OandaExecutor.getOpenTradesDetailed()` → `List<TradeView{id, instrument, units, tag}>` depuis
`GET /openTrades`. Un trade est **orphelin** si son tag commence par `<strategyShortName>_` et que son `id`
n'est pas dans `activeTrades`. Un trade **sans tag** (posé avant L1.4) n'est jamais classé orphelin : il est
compté dans `untrackedBrokerTrades` sans attribution, ce qui évite les faux positifs croisés.

Moniteur agrégé :

```json
"stateSource": "fresh",
"untrackedBrokerTrades": [{"id": "504", "instrument": "USD_CHF", "units": "-4600", "tag": "vwpreversion_USDCHF"}]
```

Log `ERROR` par orphelin (id, instrument, unités). La page Trading l'affiche, l'opérateur décide. Aucun effet
sur les ordres au lot 1.

### 4.3 Validation d'instrument

Dans `resumeState()`, avant de restaurer `activeTrades` : comparer `root.get("instrument")` (écrit `:1966`) à
`toOandaSymbol()`. Différent ou absent → `stateSource = stale-instrument`, aucune `activeTrade` restaurée,
`totalEntries/Exits` et le ledger restent restaurés (compteurs de stratégie, pas positions), log WARN nommant
les deux instruments.

### 4.4 Ce qui ne bouge pas

`monitorFile` reste dans `/tmp`. Format du fichier d'état inchangé. `hasUnconfirmedReconciliation()` (`:2098`)
et son gate (`:1151`) intacts.

## 5. Critères d'acceptation (lot 1)

1. `stateDir` vient de `live-config.json` ; `TB_STATE_DIR` le surcharge ; défaut `/tmp`. Sans surcharge, les
   deux tests existants (`LiveStrategyRunnerPnlAccountingTest:62`, `LiveStrategyRunnerStateExportTest:53`)
   restent verts.
2. Redémarrage de conteneur avec position ouverte : `stateSource: "resumed"`, `activeTrades` restauré, fichier
   présent sous `./data/state/` sur l'hôte.
3. Instrument sauvegardé ≠ résolu : `stateSource: "stale-instrument"`, aucune `activeTrade` de l'autre paire,
   log WARN avec les deux valeurs.
4. Parse en échec : `stateSource: "resume-failed"`, log **ERROR** (plus WARN), visible dans le moniteur.
5. Trade courtier taggé pour la stratégie et absent localement : listé dans `untrackedBrokerTrades` + log
   ERROR. Un trade taggé d'une autre stratégie n'y apparaît pas. Un trade sans tag est compté sans attribution.
6. Fill confirmé : état persisté immédiatement (test qui lit le fichier sans attendre 60 s).
7. `writeJsonAtomic` dégrade en move simple sur `AtomicMoveNotSupportedException`, avec WARN.
8. `docker compose config` valide, les 5 services portent le répertoire persistant.

## 6. Tests

| Test | Ce qu'il prouve |
|---|---|
| `StateDirResolutionTest` | env > config > `/tmp`, répertoire créé |
| `ResumeStateInstrumentTest` | instrument différent → `stale-instrument`, pas de restauration croisée |
| `ResumeStateFailureTest` | JSON tronqué → `resume-failed` + ERROR, pas de plat silencieux |
| `EntryPersistenceTest` | après un fill, le fichier contient le trade sans attendre le périodique |
| `OrphanDetectionTest` | tag de la stratégie → listé ; tag d'une autre → ignoré ; sans tag → compté sans attribution |
| `AtomicWriteFallbackTest` | `ATOMIC_MOVE` refusé → écriture réussie via move simple |
| Vérification manuelle | redémarrage réel avec position ouverte, `stateSource: resumed` et fichier sous `./data/state/` |

La vérification manuelle est obligatoire avant de déclarer la story finie : aucun test unitaire ne prouve que
le volume est monté.

## 7. Risques

| Risque | Parade |
|---|---|
| Premier déploiement : le fichier `/tmp` en cours est abandonné | Zéro position ouverte exigée avant `up -d`, vérifiée chez le courtier |
| Fichiers d'état écrits par root sur l'hôte (aucun `USER` dans le Dockerfile) | Lisibles par tous, non modifiables sans root : acceptable, à documenter |
| Volume sur FS sans `ATOMIC_MOVE` | Fallback L1.7 + WARN |
| Ledger restauré sans `activeTrades` (cas `stale-instrument`) | Le ledger ne couvre que les trades clôturés, donc reste valide ; WARN explicite |
| Trade sans tag invisible | Compté dans `untrackedBrokerTrades` ; taggé après L1.4 |

## 8. Déploiement

1. Vérifier **zéro position ouverte** chez le courtier.
2. Reconstruire l'image, `docker compose up -d` sur les **5** services.
3. Contrôler `stateSource`, la présence du fichier sous `./data/state/`, et l'égalité `openTrades` ↔ moniteur.
4. Rollback : repointer vers un chemin **persistant** (jamais `/tmp`), ou revenir à l'image précédente en
   gardant le volume.
