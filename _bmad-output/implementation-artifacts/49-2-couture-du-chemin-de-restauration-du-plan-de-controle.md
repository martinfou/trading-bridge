# Story 49.2 — Aucun appel courtier ne doit sortir d'un runtime de test (chemin de restauration)

- **Status : ready-for-dev** (décision D41, Martin le 2026-10-09 : story séparée)
- **Date :** 2026-10-09
- **Propriétaire :** Martin
- **baseline_commit :** `d91a1b0e`
- **Origine :** mesuré le 2026-10-09 pendant la preuve avant déploiement de 48.2 :
  `grep -c "Fetching last"` = **1** sur la suite complète (12 modules, Java 21), alors que la règle du dépôt
  dit que ce compteur doit valoir 0.

## Le défaut, avec sa preuve

```
[main] ControlPlaneMain   - Restoring active run survive-run-abc (LondonOpenRangeBreakout on EUR_USD)...
[main] RunManager         - Fetching last 500 live H1 bars from OANDA for symbol EUR_USD (to: null)...
ERROR OandaPriceClient    - OANDA API returned error status 400: {"errorMessage":"Invalid value specified for 'accountID'"}
WARN  RunManager          - Failed to fetch live candles from OANDA: ... Falling back to default bars.
```

Classe déclenchante : `PaperTradingSurvivabilityTest`
(`trading-runtime/src/test/java/com/martinfou/trading/runtime/PaperTradingSurvivabilityTest.java:74`, appel
`ControlPlaneMain.restoreActiveRuns(manager, config)`).

L'appel est en **lecture** et échoue 400 sur un `accountID` sentinelle : il ne trade pas **aujourd'hui**. Le
risque est ailleurs. `RunManager.loadBars` (l.1185) part du fetcher par défaut (`fetchOandaCandles`, l.1280),
qui construit un `OandaPriceClient` **en ligne** et lit les identifiants que l'environnement porte. Le jour où
l'environnement porte un identifiant **valide**, ce chemin ne fait plus qu'une lecture : il **restaure un run
persisté et l'exécute**. C'est exactement l'incident déjà documenté dans l'historique du projet (le run de dev
`active-run-123` qui a placé de vrais ordres practice depuis juin, sous tag UUID).

## Ce qui existe déjà

La couture **existe** depuis 48.2 : `RunManager.LiveCandlesFetcher` (l.146), champ `l.172`, défaut posé au
constructeur `l.286`, usage sur le chemin de restauration `l.548`. `RunManagerTest` l'injecte et ne touche
plus le courtier.

Ce qui manque : le chemin emprunté par `PaperTradingSurvivabilityTest` construit son `RunManager` par le
constructeur public court (`new RunManager(eventStore)`, l.28 du test), donc avec le fetcher **par défaut**.
Le test vit dans le **même paquet** que la couture, donc rien n'empêche techniquement de l'injecter : c'est un
défaut de **câblage**, pas de conception.

## Correctif proposé (deux niveaux, le second rend le premier durable)

1. **Câblage** : `PaperTradingSurvivabilityTest` construit son `RunManager` avec un `LiveCandlesFetcher`
   injecté. Rendre `null` fait retomber `loadBars` sur `BarSourceResolver` : comportement déjà prévu par la
   couture. Une surcharge `RunManager(EventStore, LiveCandlesFetcher)` package-private est acceptable si la
   liste d'arguments actuelle est impraticable.
2. **Garde** : que le défaut `fetchOandaCandles` refuse de lui-même de construire un client courtier sous un
   runtime de test, en réutilisant le prédicat existant `OrderTripwire.isTestRuntime` (une seule définition de
   « suis-je en test ? » dans le dépôt, jamais une seconde invention locale). En production le prédicat est
   faux : comportement inchangé.

Sans le niveau 2, le prochain test qui écrira `new RunManager(eventStore)` rouvre le trou : la séparation
serait tenue par la mémoire de l'opérateur, pas par une garde.

## Critères d'acceptation (mesurables)

- **AC1** : `grep -c "Fetching last" <log-surefire>` = **0** sur la suite complète (aujourd'hui 1). C'est le
  chiffre de recette, pas une impression.
- **AC2 (effet)** : `lastTransactionID` et `openTradeCount` du compte `-014` identiques avant et après la
  suite.
- **AC3 (puissance)** : un test qui repasse par le constructeur public court doit échouer (mutation) : c'est
  ce qui prouve que la garde du niveau 2 est réellement exercée.
- **AC4** : en production, `fetchOandaCandles` construit toujours un client réel quand `isTestRuntime` est
  faux.

## Hors périmètre

- Ne pas garder les **lectures** par `OrderTripwire` : il ne garde que les chemins qui **envoient** un ordre,
  par conception. Ce sont la couture et la garde de runtime qui ferment ce trou-ci.
- Aucun changement de comportement du plan de contrôle en production.
