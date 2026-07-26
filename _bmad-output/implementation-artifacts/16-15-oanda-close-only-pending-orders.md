---
baseline_commit: 57bd427763c231df37ce21c037faedb81bea208e
---
# Story 16.15: Correction des ordres en attente Close-Only sur OANDA

Status: done

## Story

En tant que trader,
je veux empêcher les ordres STOP et LIMIT "close-only" d'être exécutés immédiatement comme des fermetures au marché par le connecteur OANDA, et plutôt les soumettre comme des ordres en attente "REDUCE_ONLY",
afin que mes stratégies ne se ferment pas instantanément dès l'entrée, évitant ainsi les exécutions dupliquées et les corrections de réconciliation.

## Acceptance Criteria

1. **AC1 — Restriction des fermetures immédiates aux ordres MARKET** :
   Dans [OandaBroker.java](file:///Volumes/T7/src/trading-bridge/trading-broker/src/main/java/com/martinfou/trading/broker/OandaBroker.java), modifier la vérification du drapeau `closeOnly` pour qu'elle ne déclenche une fermeture immédiate (`client.closeTrade`) que si le type de l'ordre est `MARKET` :
   ```java
   if (order.isCloseOnly() && order.type() == Order.Type.MARKET) {
   ```

2. **AC2 — Support de REDUCE_ONLY pour les ordres STOP/LIMIT en attente** :
   - Mettre à jour l'interface `OandaRestClient` dans [OandaRestClient.java](file:///Volumes/T7/src/trading-bridge/trading-data/src/main/java/com/martinfou/trading/data/oanda/OandaRestClient.java) et ses implémentations ([HttpOandaRestClient.java](file:///Volumes/T7/src/trading-bridge/trading-data/src/main/java/com/martinfou/trading/data/oanda/HttpOandaRestClient.java), [StubOandaRestClient.java](file:///Volumes/T7/src/trading-bridge/trading-data/src/main/java/com/martinfou/trading/data/oanda/StubOandaRestClient.java), et la classe fictive `RecordingClient` dans [OandaBrokerTest.java](file:///Volumes/T7/src/trading-bridge/trading-broker/src/test/java/com/martinfou/trading/broker/OandaBrokerTest.java)) pour accepter un paramètre `reduceOnly` (ou `positionFill`).
   - Pour [HttpOandaRestClient.java](file:///Volumes/T7/src/trading-bridge/trading-data/src/main/java/com/martinfou/trading/data/oanda/HttpOandaRestClient.java), si le drapeau `reduceOnly` est vrai, ajouter le paramètre `"positionFill": "REDUCE_ONLY"` dans le corps de la requête JSON d'ordre OANDA.
   - Dans `submitOrderInternal` de [OandaBroker.java](file:///Volumes/T7/src/trading-bridge/trading-broker/src/main/java/com/martinfou/trading/broker/OandaBroker.java), lors de la soumission d'un ordre non-MARKET avec `isCloseOnly()`, passer `reduceOnly = true` à la méthode `client.placeOrder(...)`.

3. **AC3 — Validation et Tests** :
   - Ajouter des tests unitaires dans [OandaBrokerTest.java](file:///Volumes/T7/src/trading-bridge/trading-broker/src/test/java/com/martinfou/trading/broker/OandaBrokerTest.java) pour vérifier qu'un ordre `STOP` ou `LIMIT` avec `closeOnly` est correctement transmis comme un ordre en attente avec `reduceOnly = true` et ne déclenche pas de fermeture immédiate.
   - S'assurer que le projet compile et que tous les tests unitaires passent via `mvn clean install`.

## Tasks / Subtasks

- [x] Task 1: Mettre à jour l'interface OandaRestClient et ses implémentations Http/Stub (AC: 2)
  - [x] Modifier la signature de `placeOrder` pour ajouter le paramètre `reduceOnly`
  - [x] Mettre à jour `HttpOandaRestClient` pour injecter `"positionFill": "REDUCE_ONLY"` quand `reduceOnly` est vrai
  - [x] Ajuster `StubOandaRestClient`
- [x] Task 2: Adapter le comportement d'OandaBroker pour les ordres en attente closeOnly (AC: 1, 2)
  - [x] Restreindre l'interception immédiate de `isCloseOnly` aux ordres MARKET
  - [x] Transmettre `reduceOnly = true` à `placeOrder` pour les ordres STOP/LIMIT qui ont `isCloseOnly`
- [x] Task 3: Adapter les tests et objets fictifs dans OandaBrokerTest (AC: 3)
  - [x] Mettre à jour la classe interne `RecordingClient`
  - [x] Écrire un test vérifiant qu'un ordre STOP closeOnly n'est pas exécuté comme une fermeture immédiate
- [x] Task 4: Lancement des tests et validation (AC: 3)
  - [x] Lancer la commande `mvn clean install` sur les modules modifiés

## Dev Notes

- Le problème initial a été diagnostiqué dans le fichier de cas [duplicate-trades-2026-07-24-investigation.md](file:///Volumes/T7/src/trading-bridge/_bmad-output/implementation-artifacts/investigations/duplicate-trades-2026-07-24-investigation.md).
- Spécificités de l'API OANDA : L'API d'ordres accepte le champ `positionFill` avec les valeurs `"DEFAULT"`, `"OPEN_ONLY"`, `"REDUCE_ONLY"`, `"REDUCE_FIRST"`. La valeur `"REDUCE_ONLY"` permet de garantir que l'ordre en attente réduira la position opposée sans jamais en ouvrir une nouvelle, et s'annulera s'il n'y a plus de position.

### Project Structure Notes

- Respecter le pattern de l'interface `Broker` et déléguer les détails HTTP de l'API OANDA à `HttpOandaRestClient` dans le module `trading-data`.

### References

- Diagnostic de base : [duplicate-trades-2026-07-24-investigation.md](file:///Volumes/T7/src/trading-bridge/_bmad-output/implementation-artifacts/investigations/duplicate-trades-2026-07-24-investigation.md)
- Implémentation du courtier : [OandaBroker.java](file:///Volumes/T7/src/trading-bridge/trading-broker/src/main/java/com/martinfou/trading/broker/OandaBroker.java)
- Client REST OANDA : [HttpOandaRestClient.java](file:///Volumes/T7/src/trading-bridge/trading-data/src/main/java/com/martinfou/trading/data/oanda/HttpOandaRestClient.java)

## Dev Agent Record

### Agent Model Used

Gemini 3.5 Flash (High)

### Debug Log References

- file:///Users/martinfou/.gemini/antigravity-ide/brain/c6df5739-ec74-4e84-9730-7d938fce00c8/.system_generated/tasks/task-297.log

### Completion Notes List

- Implémentation de la restriction `closeOnly` aux seuls ordres `MARKET` dans `OandaBroker.java`.
- Ajout du support de `reduceOnly` dans `placeOrder` (signature de l'interface `OandaRestClient` et implémentations).
- Injection de `"positionFill": "REDUCE_ONLY"` dans le payload d'ordre envoyé à OANDA.
- Ajout de tests de non-régression et de tests spécifiques pour `reduceOnly` et l'interception `closeOnly`.
- Exécution réussie des 146 tests du projet via `mvn clean test`.

### File List

- `trading-broker/src/main/java/com/martinfou/trading/broker/OandaBroker.java`
- `trading-data/src/main/java/com/martinfou/trading/data/oanda/OandaRestClient.java`
- `trading-data/src/main/java/com/martinfou/trading/data/oanda/HttpOandaRestClient.java`
- `trading-data/src/main/java/com/martinfou/trading/data/oanda/StubOandaRestClient.java`
- `trading-data/src/test/java/com/martinfou/trading/data/oanda/HttpOandaRestClientTest.java`
- `trading-broker/src/test/java/com/martinfou/trading/broker/OandaBrokerTest.java`

### Change Log

- 2026-07-26: Correction des ordres en attente closeOnly et intégration positionFill: REDUCE_ONLY sur l'API OANDA.
