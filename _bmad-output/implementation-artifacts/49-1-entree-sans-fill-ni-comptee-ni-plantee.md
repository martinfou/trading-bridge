# Story 49.1 — Une entrée sans fill ne doit ni compter ni planter

- **Status : ready-for-dev** (décision D40, Martin le 2026-10-09 : story BMad, revue indépendante avant livraison)
- **Date :** 2026-10-09
- **Propriétaire :** Martin
- **baseline_commit :** `d91a1b0e`
- **Origine :** perte de signal réelle le 2026-10-08, diagnostiquée le 2026-10-09 en relisant le journal du
  runner pendant la préparation du déploiement 48.2. Ce n'est PAS le défaut décrit par la story `41-1`.

## Le déclencheur, mesuré

Le 2026-10-08 à 21:00:24 UTC (17:00:24 EDT), `consecbar` produit un signal d'entrée sur GBP_JPY. La
transaction `MARKET_ORDER` existe bien chez OANDA (tag `consecbar_GBP_JPY`) et le courtier l'**annule** avec
le motif `MARKET_HALTED` : la bascule quotidienne de 17h00 ET suspend brièvement la cotation, donc l'ordre ne
se remplit pas. Journal du runner :

```
21:00:24.269 ═══════ ENTRY GBP_JPY BUY 0.05 lots @ N/A (stop on fill: 208.632) ═══════
21:00:24.270 ❌ TRADE EXECUTION FAILED: GBP_JPY BUY @ 208.959 — For input string: "N/A"
```

Un signal est perdu sans reprise (la barre suivante ne le rattrape pas), et le message d'erreur n'oriente pas
vers la vraie cause. Aucun risque financier : rien ne se remplit, aucune position ne reste sans surveillance.

## Mécanisme exact (lu dans le code, pas déduit)

1. `OandaExecutor.placeMarketOrder` (`trading-strategies/.../OandaExecutor.java`, l.106-112) rend un
   `OrderResult` dont le prix de fill est la **chaîne sentinelle `"N/A"`** quand `orderFillTransaction` est
   absent (l.109-110 : `orderFill != null ? orderFill.get("price").asText() : "N/A"`). Le statut rendu est
   `"FILLED"` **dans tous les cas**, y compris sans fill (l.111).
2. `LiveStrategyRunner.executeTrade` journalise ce sentinel (l.1314, la ligne `ENTRY ... @ N/A`),
   **incrémente `totalEntries` (l.1312)**, puis appelle `Double.parseDouble(result.fillPrice())` (l.1320) :
   `NumberFormatException` sur `"N/A"`.
3. L'exception est avalée par le `catch (Exception e)` (l.1331-1334) qui journalise
   `TRADE EXECUTION FAILED ... — For input string: "N/A"`, ce qui **masque la cause réelle**.

**Conséquence mesurable de l'incrément avant le parse :** l'état persisté de `consecbar` porte
`totalEntries: 4` et `totalExits: 3` avec `activeTrades: []`. La 4e « entrée » n'a jamais existé : c'est
l'ordre annulé du 2026-10-08. Le compteur d'entrées est faux, et c'est ce que le moniteur publie.

## Ce qui existe déjà, et pourquoi ce n'est pas couvert

Story `41-1` (done) couvre la résilience du **parsing** : JSON malformé, nœuds absents, exceptions de
transport → `OandaApiException` propre. Ici le payload est **valide** et l'absence est **notre** sentinel,
produit par `OandaExecutor`. Contrat différent : ne pas étendre 41-1.

## Correctif proposé

1. **Supprimer le sentinel de prix.** `OrderResult.fillPrice()` ne doit plus pouvoir rendre `"N/A"` : rendre
   une absence explicite (`OptionalDouble`, ou une exception typée) et un statut qui distingue `FILLED` de
   `NOT_FILLED` (aujourd'hui le statut est `FILLED` même sans fill).
2. **Le chemin d'entrée traite l'absence comme une non-opportunité** : `WARN` nommant la cause, aucun ordre
   de suivi, **aucun incrément de `totalEntries`**, aucun cooldown armé. La barre suivante réévalue
   normalement.
3. **Remonter la raison du courtier.** `orderCancelTransaction.reason` (`MARKET_HALTED` ici) existe dans la
   réponse OANDA et n'apparaît nulle part dans le journal du runner. Le message doit la porter.
4. Vérifier tous les lecteurs du prix de fill (aujourd'hui un seul appelant : `LiveStrategyRunner:1320`) et
   les lecteurs du statut.

## Décision demandée

Un seul vrai choix de conception : **sauter** (proposé) ou **réessayer dans la même barre** après la levée du
halt. Sauter est plus simple et ne change pas la fréquence de trading validée ; réessayer rattrape le signal
mais introduit un comportement que le backtest n'a jamais modélisé. Une ligne suffit : `sauter` ou
`réessayer`.

## Critères d'acceptation

- **AC1** : aucun chemin ne peut appeler `Double.parseDouble` sur un prix de fill absent. Test unitaire sur
  `OandaExecutor` avec une réponse sans `orderFillTransaction`.
- **AC2** : sur une entrée non remplie, aucun ordre n'est envoyé, `totalEntries` n'est pas incrémenté, et le
  `WARN` nomme la raison du courtier.
- **AC3 (puissance)** : le test AC2 doit **échouer** si on remet le sentinel (mutation), pas seulement
  passer.
- **AC4** : le compteur d'entrées persisté reste cohérent avec les fills réels ; le cas `consecbar` du
  2026-10-08 est le cas de test.

## Fichiers cibles

- `trading-strategies/src/main/java/com/martinfou/trading/strategies/OandaExecutor.java` (l.106-112)
- `trading-strategies/src/main/java/com/martinfou/trading/strategies/LiveStrategyRunner.java`
  (l.1312, 1314, 1320, 1331-1334)
- tests JUnit 5 du module `trading-strategies` (`trading-strategies/src/test/java/com/martinfou/trading/strategies/`)

## Hors périmètre

- Ne pas changer la logique de signal des stratégies, le sizing, les instruments, ni la liste des services.
- Ne pas ajouter de retry intra-barre sans la décision ci-dessus.
