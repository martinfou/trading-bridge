# Story 41.1: resilience-du-parsing-json-et-gestion-des-exceptions-oanda

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a Martin (opérateur),
I want OandaPriceClient to validate JSON fields defensively and catch parse exceptions to throw a sanitized OandaApiException,
so that transient OANDA schema changes do not crash execution threads or leak raw payloads to public endpoints.

## Acceptance Criteria

1. **BrokerException de base** : Créer `com.martinfou.trading.core.exceptions.BrokerException` (héritant de `RuntimeException`) dans le module `trading-core`.
2. **Exception spécifique OANDA** : Créer `com.martinfou.trading.data.OandaApiException` (héritant de `BrokerException`) dans le module `trading-data`.
3. **Validations JSON défensives** : Dans `OandaPriceClient.getPrice`, `getCandlesBefore` et `getAccountSummary`, valider la présence de tous les nœuds requis via `.has()` et la taille des tableaux (ex. `.size() > 0`) avant de tenter d'accéder aux index (comme `.get(0)`), afin de prévenir les `NullPointerException`.
4. **Scrubbing & Sanitization** : Intercepter toutes les exceptions de parsing Jackson (`JsonProcessingException`) et HttpClient (`IOException` / `InterruptedException`) dans la méthode privée `get` de `OandaPriceClient.java`. Consigner (log) le corps de la réponse brute localement au niveau `ERROR` (uniquement dans les fichiers de log locaux). Lever une exception détaillée `OandaApiException` sans exposer le corps brut ni de secrets d'authentification dans le message public de l'exception.
5. **Protection du Thread de Runner** : Mettre à jour `LiveStrategyRunner.run()` (module `trading-strategies`). Intercepter `BrokerException` pour consigner et gérer les tentatives de reconnexion ou de relance. Intercepter toutes les autres exceptions non gérées au niveau supérieur pour s'assurer que `RUNNING.set(false)` est appelé, que l'état persistant est défini sur `FAILED` et qu'aucune mort silencieuse du thread ne se produit.
6. **Tests Unitaires JUnit 5** : Implémenter des tests dans `OandaPriceClientTest.java` (module `trading-data`) pour vérifier que l'exception `OandaApiException` est correctement levée lors de la réception d'un JSON mal structuré ou vide.

## Tasks / Subtasks

- [ ] Task 1 : Créer la classe d'exception générique du broker (AC: 1)
  - [ ] Créer le fichier [BrokerException.java](file:///Volumes/T7/src/trading-bridge/trading-core/src/main/java/com/martinfou/trading/core/exceptions/BrokerException.java) étendant `RuntimeException`.
- [ ] Task 2 : Créer la classe d'exception spécifique à OANDA (AC: 2)
  - [ ] Créer le fichier [OandaApiException.java](file:///Volumes/T7/src/trading-bridge/trading-data/src/main/java/com/martinfou/trading/data/OandaApiException.java) étendant `BrokerException`.
- [ ] Task 3 : Ajouter des validations défensives et gérer le parsing dans OandaPriceClient.java (AC: 3, 4)
  - [ ] Modifier [OandaPriceClient.java](file:///Volumes/T7/src/trading-bridge/trading-data/src/main/java/com/martinfou/trading/data/OandaPriceClient.java) pour vérifier défensivement la présence des nœuds JSON requis dans `getPrice()`, `getCandlesBefore()` et `getAccountSummary()`.
  - [ ] Intercepter les exceptions de transport et de désérialisation JSON dans la méthode privée `get()`, consigner le payload brut localement et lever `OandaApiException`.
- [ ] Task 4 : Sécuriser la boucle d'exécution et gérer la mort de thread dans LiveStrategyRunner.java (AC: 5)
  - [ ] Modifier `run()` et `runLoop()` dans [LiveStrategyRunner.java](file:///Volumes/T7/src/trading-bridge/trading-strategies/src/main/java/com/martinfou/trading/strategies/LiveStrategyRunner.java) pour capturer les exceptions et mettre à jour le statut persistant à `FAILED` en cas d'erreur fatale.
- [ ] Task 5 : Implémenter les tests unitaires JUnit 5 (AC: 6)
  - [ ] Créer le fichier [OandaPriceClientTest.java](file:///Volumes/T7/src/trading-bridge/trading-data/src/test/java/com/martinfou/trading/data/OandaPriceClientTest.java) pour valider le comportement sur des réponses JSON vides ou corrompues.

## Dev Notes

- **Conventions d'Exception** : Les exceptions doivent être des unchecked exceptions (`RuntimeException`) pour éviter d'alourdir les signatures des méthodes de trading existantes.
- **Journalisation Sécurisée** : Les payloads de réponse brute contiennent des informations sensibles (comme l'ID de compte) ; ils ne doivent figurer que dans les logs d'erreurs locaux de la JVM (`log.error`), et ne doivent jamais être encapsulés dans le message String brut d'une exception propagée à l'API HTTP publique.
- **Localisation des fichiers** :
  - `BrokerException` : `trading-core/src/main/java/com/martinfou/trading/core/exceptions/BrokerException.java`
  - `OandaApiException` : `trading-data/src/main/java/com/martinfou/trading/data/OandaApiException.java`
  - `OandaPriceClientTest` : `trading-data/src/test/java/com/martinfou/trading/data/OandaPriceClientTest.java`

### Project Structure Notes

- Le module `trading-core` ne dépend d'aucun autre module interne. C'est l'emplacement idéal pour la classe de base `BrokerException` afin de permettre son utilisation ultérieure par d'autres connecteurs (comme `trading-broker` ou `IBKR`).
- Le module `trading-data` a pour dépendance `trading-core`, ce qui lui permet d'étendre `BrokerException` pour créer `OandaApiException`.

### References

- [epics.md](file:///Volumes/T7/src/trading-bridge/_bmad-output/planning-artifacts/epics.md#L159-163) (FR34)
- [OandaPriceClient.java](file:///Volumes/T7/src/trading-bridge/trading-data/src/main/java/com/martinfou/trading/data/OandaPriceClient.java#L41-51) (getPrice parsing)
- [LiveStrategyRunner.java](file:///Volumes/T7/src/trading-bridge/trading-strategies/src/main/java/com/martinfou/trading/strategies/LiveStrategyRunner.java#L549-555) (Thread run loop)

## Dev Agent Record

### Agent Model Used

gemini-1.5-pro

### Debug Log References

### Completion Notes List

### File List
