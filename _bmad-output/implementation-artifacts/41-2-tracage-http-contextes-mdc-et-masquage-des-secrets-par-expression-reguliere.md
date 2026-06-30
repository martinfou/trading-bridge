# Story 41.2: tracage-http-contextes-mdc-et-masquage-des-secrets-par-expression-reguliere

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a developer,
I want HTTP requests and responses logged at TRACE level with SLF4J MDC context and a global regex credentials scrubber,
so that I can trace API messages without exposing authentication tokens.

## Acceptance Criteria

1. **Traçage des Requêtes HTTP (TRACE)** : Lorsque le niveau de journalisation `TRACE` est activé, consigner les détails de toutes les requêtes REST OANDA exécutées par le client HTTP (URI, méthode, en-têtes et corps de la requête).
2. **Traçage des Réponses HTTP (TRACE)** : Lorsque le niveau de journalisation `TRACE` est activé, consigner les détails des réponses reçues d'OANDA (code statut HTTP, en-têtes et corps de la réponse).
3. **Scrubber de Secrets par Expression Régulière** : Utiliser un filtre regex global sur tous les messages de trace HTTP pour détecter et masquer toute chaîne correspondant à la structure d'une clé API OANDA (regex : `[a-fA-F0-9]{64}`). Remplacer ces occurrences par la chaîne `[MASKED]` afin d'éviter l'exposition accidentelle de secrets d'authentification dans les fichiers de log.
4. **Contextes de Journalisation MDC** : Associer systématiquement les valeurs MDC (Mapped Diagnostic Context) SLF4J pour `runId`, `strategyId` et `symbol` à tous les logs émis lors de l'exécution des stratégies de trading.
5. **Nettoyage Automatique du Contexte MDC** : Appeler systématiquement `MDC.clear()` (ou supprimer spécifiquement les clés MDC configurées) dans un bloc `finally` à la fin de la boucle d'exécution de chaque thread de stratégie (`LiveStrategyRunner`) afin de prévenir les fuites de contexte de log vers d'autres threads réutilisés par le pool de threads de la JVM.

## Tasks / Subtasks

- [ ] Task 1 : Implémenter le scrubber de secrets regex (AC: 3)
  - [ ] Ajouter une méthode utilitaire `scrub(String input)` dans `HttpOandaRestClient.java` remplaçant les chaînes de 64 caractères hexadécimaux par `[MASKED]`.
- [ ] Task 2 : Ajouter le traçage HTTP TRACE dans HttpOandaRestClient (AC: 1, 2)
  - [ ] Mettre à jour `sendWithRetry()` dans [HttpOandaRestClient.java](file:///Volumes/T7/src/trading-bridge/trading-data/src/main/java/com/martinfou/trading/data/oanda/HttpOandaRestClient.java) pour consigner les détails complets de la requête (URI, méthode, en-têtes, corps) et de la réponse (status, en-têtes, corps) au niveau `TRACE` via le scrubber de secrets.
  - [ ] Ajouter une méthode utilitaire pour extraire le corps de la requête depuis le `BodyPublisher` de l'instance `HttpRequest`.
- [ ] Task 3 : Configurer le contexte MDC SLF4J dans LiveStrategyRunner (AC: 4, 5)
  - [ ] Générer un `runId` unique (ex: un ID UUID court ou horodaté) dans le constructeur de [LiveStrategyRunner.java](file:///Volumes/T7/src/trading-bridge/trading-strategies/src/main/java/com/martinfou/trading/strategies/LiveStrategyRunner.java).
  - [ ] Renseigner les clés MDC `runId` (avec le run ID généré), `strategyId` (avec `strategyShortName`) et `symbol` (avec `toOandaSymbol()`) au début de la méthode `run()` de [LiveStrategyRunner.java](file:///Volumes/T7/src/trading-bridge/trading-strategies/src/main/java/com/martinfou/trading/strategies/LiveStrategyRunner.java).
  - [ ] S'assurer que `MDC.clear()` est bien appelé dans un bloc `finally` de `run()` pour nettoyer le thread.
- [ ] Task 4 : Écrire des tests unitaires pour le scrubber et MDC (AC: 3, 5)
  - [ ] Mettre à jour [HttpOandaRestClientTest.java](file:///Volumes/T7/src/trading-bridge/trading-data/src/test/java/com/martinfou/trading/data/oanda/HttpOandaRestClientTest.java) pour valider le masquage correct des clés API de 64 caractères dans les traces HTTP.

## Dev Notes

- **Regex de Masquage** : Le pattern regex à appliquer est `[a-fA-F0-9]{64}`. Il doit cibler non seulement le header `Authorization` mais également les URL (si des secrets y transitent) et tout log affiché.
- **Récupération du corps du BodyPublisher** : Pour extraire de manière synchrone le corps d'une requête Java `HttpRequest` dans `sendWithRetry`, on peut utiliser un abonné factice (`Flow.Subscriber` / `HttpResponse.BodySubscribers.ofString`) sur le `BodyPublisher` de la requête.
- **Cycle de Vie du MDC** : Les frameworks de logging comme Log4j2/Logback utilisent des variables `ThreadLocal` pour stocker le contexte MDC. Si les threads de stratégie sont gérés ou réutilisés par un exécuteur (`ExecutorService`), l'omission de `MDC.clear()` répandra les anciens ID de run et de stratégie sur les exécutions suivantes. Le bloc `finally` dans la méthode `run()` de `LiveStrategyRunner` est obligatoire et non-négociable.

### Project Structure Notes

- `HttpOandaRestClient` utilise le framework de logging standard SLF4J, ce qui permet d'effectuer les écritures `TRACE` sans changer les dépendances existantes.
- Le fichier `log4j2.xml` de `trading-runtime` contient déjà le pattern incluant `%X{runId}` et `%X{strategyId}`. Nous devons nous assurer que les logs de `trading-strategies` (où réside `LiveStrategyRunner`) l'utilisent également si nécessaire, ou que le pattern par défaut intègre ces variables MDC.

### References

- [epics.md](file:///Volumes/T7/src/trading-bridge/_bmad-output/planning-artifacts/epics.md#L2225-2239) (Story 41.2 Definition)
- [HttpOandaRestClient.java](file:///Volumes/T7/src/trading-bridge/trading-data/src/main/java/com/martinfou/trading/data/oanda/HttpOandaRestClient.java#L84) (sendWithRetry)
- [LiveStrategyRunner.java](file:///Volumes/T7/src/trading-bridge/trading-strategies/src/main/java/com/martinfou/trading/strategies/LiveStrategyRunner.java#L549) (run thread execution)

## Dev Agent Record

### Agent Model Used

gemini-1.5-pro

### Debug Log References

### Completion Notes List

### File List
