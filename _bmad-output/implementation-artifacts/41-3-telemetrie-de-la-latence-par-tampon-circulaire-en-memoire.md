# Story 41.3: telemetrie-de-la-latence-par-tampon-circulaire-en-memoire

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a Martin (opérateur),
I want OANDA REST latencies measured via a monotonic timer and kept in an in-memory circular ring buffer,
so that average/max latency metrics are accessible on the dashboard without database overhead.

## Acceptance Criteria

1. **Mesure Monotonique de la Latence** : Mesurer le temps d'exécution de chaque appel API HTTP vers OANDA (dans `OandaPriceClient` et `HttpOandaRestClient`) en utilisant `System.nanoTime()` de manière défensive dans un bloc `finally` pour garantir le calcul même en cas de timeout ou d'exception de communication.
2. **Structure de Tampon Circulaire (Circular Ring Buffer)** : Implémenter une structure de tampon circulaire non-bloquante, thread-safe, et de capacité fixe (taille 10) pour conserver les 10 dernières latences mesurées.
3. **Mise à Jour de status de SqBridgeService** : Intégrer les statistiques de latence dans l'endpoint REST `/api/sq-bridge/status` en modifiant `SqBridgeService.status()`. L'API doit retourner la moyenne (`latencyAverageMs`) et le maximum (`latencyMaxMs`) des latences en millisecondes actuellement présentes dans le tampon.
4. **Zéro Persistance Database (Mémoire Uniquement)** : Conserver le tampon strictement en mémoire volatile pour éviter toute surcharge de lecture/écriture sur SQLite ou des goulots d'étranglement E/S.
5. **Tests de Performance / Validation Unitaires** : Rédiger un test unitaireJUnit 5 qui simule des insertions concurrentes sur le tampon circulaire et vérifie que la moyenne et le maximum retournés sont mathématiquement exacts et résilients aux débordements d'index (overflow).

## Tasks / Subtasks

- [ ] Task 1 : Implémenter le tampon circulaire thread-safe non-bloquant (AC: 2, 4)
  - [ ] Créer le fichier [CircularLatencyBuffer.java](file:///Volumes/T7/src/trading-bridge/trading-core/src/main/java/com/martinfou/trading/core/metrics/CircularLatencyBuffer.java) dans le module `trading-core`.
  - [ ] Gérer l'incrémentation d'index atomique lock-free (`AtomicInteger`) et le calcul de modulo sécurisé contre les débordements d'entiers (integer overflow).
- [ ] Task 2 : Exposer un singleton ou registre global de télémétrie (AC: 4)
  - [ ] Créer le fichier [LatencyTelemetry.java](file:///Volumes/T7/src/trading-bridge/trading-core/src/main/java/com/martinfou/trading/core/metrics/LatencyTelemetry.java) exposant un tampon circulaire statique global pour OANDA.
- [ ] Task 3 : Instrumenter les clients REST OANDA (AC: 1)
  - [ ] Modifier la méthode privée `get` dans [OandaPriceClient.java](file:///Volumes/T7/src/trading-bridge/trading-data/src/main/java/com/martinfou/trading/data/OandaPriceClient.java) pour mesurer la latence réseau avec `System.nanoTime()` dans un bloc `finally` et l'enregistrer dans le tampon.
  - [ ] Modifier la méthode `sendWithRetry` dans [HttpOandaRestClient.java](file:///Volumes/T7/src/trading-bridge/trading-data/src/main/java/com/martinfou/trading/data/oanda/HttpOandaRestClient.java) pour instrumenter de manière identique les requêtes du client rest v20 d'OANDA.
- [ ] Task 4 : Exposer la latence moyenne/max sur l'API REST de Control Plane (AC: 3)
  - [ ] Modifier la méthode `status()` dans [SqBridgeService.java](file:///Volumes/T7/src/trading-bridge/trading-runtime/src/main/java/com/martinfou/trading/runtime/SqBridgeService.java) pour y insérer les clés `latencyAverageMs` et `latencyMaxMs` lues depuis le tampon.
- [ ] Task 5 : Rédiger les tests unitaires JUnit 5 (AC: 5)
  - [ ] Créer le fichier `CircularLatencyBufferTest.java` dans le module `trading-core` pour tester l'exactitude des calculs de moyenne/max sous haute concurrence et en cas d'overflow d'index.

## Dev Notes

- **Formule de conversion** : Convertir les nanosecondes de `System.nanoTime()` en millisecondes en divisant la somme par `1_000_000.0` (exposer des valeurs sous forme de `double` de précision).
- **Prévention d'overflow d'index** : En Java, `AtomicInteger.getAndIncrement()` peut déborder et renvoyer une valeur négative. Pour éviter une `ArrayIndexOutOfBoundsException`, appliquez `Math.abs(index.getAndIncrement() % capacity)`.
- **Mémoire volatile** : Pas de variables persistées, de sorte que le redémarrage du runtime réinitialise simplement le tampon.

### Project Structure Notes

- Le tampon circulaire doit être placé dans le module `trading-core` afin d'être partagé entre `trading-data` (pour l'écriture) et `trading-runtime` (pour l'exposition REST via `SqBridgeService`).

### References

- [epics.md](file:///Volumes/T7/src/trading-bridge/_bmad-output/planning-artifacts/epics.md#L2240-2253) (Story 41.3 Definition)
- [OandaPriceClient.java](file:///Volumes/T7/src/trading-bridge/trading-data/src/main/java/com/martinfou/trading/data/OandaPriceClient.java#L92) (get method request execution)
- [HttpOandaRestClient.java](file:///Volumes/T7/src/trading-bridge/trading-data/src/main/java/com/martinfou/trading/data/oanda/HttpOandaRestClient.java#L84) (sendWithRetry request execution)
- [SqBridgeService.java](file:///Volumes/T7/src/trading-bridge/trading-runtime/src/main/java/com/martinfou/trading/runtime/SqBridgeService.java#L106) (status method)

## Dev Agent Record

### Agent Model Used

gemini-1.5-pro

### Debug Log References

### Completion Notes List

### File List
