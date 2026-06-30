# Story 41.4: queue-de-reconciliation-asynchrone-regulee

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a Martin (opérateur),
I want completed trade reconciliation tasks queued and processed asynchronously,
so that local trade entries/exits do not block on OANDA history REST calls and we respect API rate limits.

## Acceptance Criteria

1. **Queue de Réconciliation Asynchrone** : Dès qu'une fermeture de position (exit) est détectée localement, le thread du runner de stratégie (`LiveStrategyRunner`) doit ajouter une tâche de réconciliation dans une queue thread-safe (`LinkedBlockingQueue`) plutôt que d'effectuer l'appel bloquant d'obtention de l'historique d'OANDA directement.
2. **Worker Arrière-Plan à Débit Régulé (1 req/s max)** : Implémenter un worker monothread en arrière-plan (`ExecutorService` monothread dédié) qui consomme cette queue de réconciliation. Ce worker doit limiter son traitement à un taux maximal de 1 requête par seconde (ex: en appliquant une pause `Thread.sleep(1000)` entre chaque traitement) afin de respecter le quota d'API et d'éviter les surcharges d'appels.
3. **Limiteur de Débit Passerelle (Rate Limiter) avec Priorités** : Mettre en place un limiteur de débit centralisé (ex: Token Bucket) au niveau de la passerelle OANDA (`HttpOandaRestClient` / `OandaPriceClient`). Les requêtes à haute priorité (lectures de prix et envois d'ordres d'entrée/sortie) doivent contourner la queue asynchrone et obtenir la priorité sur les jetons disponibles. Les requêtes à basse priorité (récupération de l'historique des transactions pour réconciliation) ne doivent consommer de jetons que si le niveau du bucket reste au-dessus d'une marge de sécurité (ex: au moins 20% des jetons de la capacité maximale du bucket doivent être réservés aux requêtes haute priorité).
4. **Non-Blocage de l'Exécution Locale** : S'assurer que le thread principal de la stratégie (`LiveStrategyRunner.runLoop`) reste fluide et autonome pour détecter les nouveaux bars et gérer les signaux, sans jamais subir de latence réseau due à des appels de réconciliation lents ou défaillants.

## Tasks / Subtasks

- [ ] Task 1 : Créer le limiteur de débit centralisé (Rate Limiter) (AC: 3)
  - [ ] Créer la classe thread-safe [OandaRateLimiter.java](file:///Volumes/T7/src/trading-bridge/trading-data/src/main/java/com/martinfou/trading/data/oanda/OandaRateLimiter.java) utilisant un algorithme Token Bucket.
  - [ ] Définir une méthode `acquire(boolean highPriority)` où les requêtes basse priorité requièrent un seuil minimum de jetons dans le bucket (ex: 20 jetons sur une capacité de 100).
- [ ] Task 2 : Intégrer le rate limiter dans HttpOandaRestClient et OandaPriceClient (AC: 3)
  - [ ] Modifier la méthode `sendWithRetry` dans [HttpOandaRestClient.java](file:///Volumes/T7/src/trading-bridge/trading-data/src/main/java/com/martinfou/trading/data/oanda/HttpOandaRestClient.java) pour acquérir un jeton haute priorité pour les ordres et un jeton basse priorité pour les transactions/recherche d'historique.
  - [ ] Modifier le client [OandaPriceClient.java](file:///Volumes/T7/src/trading-bridge/trading-data/src/main/java/com/martinfou/trading/data/OandaPriceClient.java) pour acquérir un jeton haute priorité lors des lectures de prix/bougies.
- [ ] Task 3 : Créer le service de réconciliation asynchrone régulé (AC: 1, 2)
  - [ ] Créer [AsyncReconciliationQueue.java](file:///Volumes/T7/src/trading-bridge/trading-strategies/src/main/java/com/martinfou/trading/strategies/AsyncReconciliationQueue.java) gérant la queue et le worker d'arrière-plan monothread.
  - [ ] Implémenter le délai de 1 seconde entre les appels dans le worker.
- [ ] Task 4 : Connecter LiveStrategyRunner à la queue asynchrone (AC: 1, 4)
  - [ ] Modifier `updatePositions` dans [LiveStrategyRunner.java](file:///Volumes/T7/src/trading-bridge/trading-strategies/src/main/java/com/martinfou/trading/strategies/LiveStrategyRunner.java) pour soumettre des tâches de réconciliation à la queue asynchrone dès qu'une sortie de trade local est détectée.
- [ ] Task 5 : Rédiger les tests d'intégration et de validation JUnit 5 (AC: 5)
  - [ ] Créer `OandaRateLimiterTest.java` dans le module `trading-data` pour s'assurer que les requêtes haute priorité passent en cas de contention de jetons tandis que les requêtes basse priorité sont bloquées/mises en attente.

## Dev Notes

- **Dimensionnement du Token Bucket** : Configurer le bucket avec une capacité de 120 jetons (limite standard d'OANDA pour les comptes réels) et une recharge de 120 jetons par seconde.
- **Gestion des Temps d'Attente** : Si le rate limiter refuse un appel basse priorité, la tâche de réconciliation asynchrone doit être remise en queue ou faire l'objet d'un nouvel essai ultérieur avec un backoff approprié.
- **Choke-point** : Le worker asynchrone doit être unique et partagé par tous les threads de stratégie lancés, de sorte que la limite globale de 1 req/s pour la réconciliation soit respectée globalement à l'échelle de la JVM.

### Project Structure Notes

- `OandaRateLimiter` doit vivre dans `trading-data` (dans le package `com.martinfou.trading.data.oanda` ou `com.martinfou.trading.data`) car c'est là que résident les clients HTTP OANDA qui l'appelleront.
- `AsyncReconciliationQueue` peut vivre dans `trading-strategies` pour coordonner la réconciliation asynchrone des runners.

### References

- [epics.md](file:///Volumes/T7/src/trading-bridge/_bmad-output/planning-artifacts/epics.md#L2254-2267) (Story 41.4 Definition)
- [LiveStrategyRunner.java](file:///Volumes/T7/src/trading-bridge/trading-strategies/src/main/java/com/martinfou/trading/strategies/LiveStrategyRunner.java#L869) (updatePositions method)
- [HttpOandaRestClient.java](file:///Volumes/T7/src/trading-bridge/trading-data/src/main/java/com/martinfou/trading/data/oanda/HttpOandaRestClient.java#L84) (sendWithRetry method)

## Dev Agent Record

### Agent Model Used

gemini-1.5-pro

### Debug Log References

### Completion Notes List

### File List
