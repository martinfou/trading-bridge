# Story 41.6: telemetrie-du-signal-et-watchdog-de-liveness

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a Martin (opérateur),
I want strategy indicator values logged only on decision state changes, and runner threads monitored with active alerts on death,
so that logs are concise and thread deaths are flagged immediately as system failures with stack traces.

## Acceptance Criteria

1. **Journalisation Filtre des Indicateurs (Signal State Log)** : Dans le runner de stratégie (`LiveStrategyRunner`), évaluer le signal de décision de la stratégie (ex: `HOLD`, `BUY`, `SELL`). Consigner les valeurs d'indicateurs et les conditions de règles associées *uniquement* lors d'un changement d'état du signal de décision (ex: transition de `HOLD` à `BUY`, ou de `BUY` à `HOLD`). Les logs répétitifs sur chaque bar inchangé doivent être supprimés.
2. **Scrubbing de NaN et Infini dans la Sérialisation** : Lors de la sérialisation périodique de l'état (toutes les 60 secondes), intercepter et remplacer toutes les valeurs de type double valant `Double.NaN`, `Double.POSITIVE_INFINITY` ou `Double.NEGATIVE_INFINITY` par `0.0`. Cela garantit la conformité du format JSON produit et évite les erreurs de parsing sur le dashboard.
3. **Watchdog de Liveness JVM** : Implémenter un watchdog de liveness dans le planificateur de Control Plane (`StaleRunWatchdog` / `RunManager`). Le watchdog doit périodiquement vérifier l'état d'activité des threads de runner (`Thread.isAlive()`). Si un thread associé à un run marqué `RUNNING` s'arrête de manière inattendue (crash), le statut du run doit transiter vers `FAILED` dans la base de données.
4. **Thread Dump Limité en Cas de Crash** : En cas de crash d'un thread de runner, le watchdog doit générer et enregistrer un thread dump complet dans les logs système (limité à maximum un thread dump toutes les 5 minutes par run pour éviter d'inonder les fichiers de log).
5. **Alerte de Crash Asynchrone (Event Store & WebSockets)** : Enregistrer un événement `RUN_CRASHED` de manière asynchrone dans l'Event Store SQLite lors d'un crash, et diffuser immédiatement une alerte sur le canal WebSocket du Control Plane pour déclencher des alertes visuelles et sonores sur le dashboard.
6. **Exponential Backoff sur Redémarrages en Boucle** : Si une stratégie échoue à répétition dans les 30 secondes suivant son démarrage, le watchdog doit imposer un délai d'attente exponentiel (ex: 2s, 4s, 8s, 16s, etc.) avant de tenter le redémarrage automatique suivant pour éviter d'inonder l'API OANDA.

## Tasks / Subtasks

- [ ] Task 1 : Implémenter la journalisation sélective des indicateurs par réflexion (AC: 1)
  - [ ] Modifier [LiveStrategyRunner.java](file:///Volumes/T7/src/trading-bridge/trading-strategies/src/main/java/com/martinfou/trading/strategies/LiveStrategyRunner.java) pour enregistrer le dernier signal produit par la stratégie (déterminé par la présence et le sens des ordres retournés par `getPendingOrders()`).
  - [ ] Détecter les changements d'état et journaliser toutes les variables de type double et booléen de la stratégie par réflexion uniquement lors de ces transitions.
- [ ] Task 2 : Configurer le filtre de sérialisation Jackson (AC: 2)
  - [ ] Enregistrer un module de sérialisation personnalisé dans l'ObjectMapper Jackson (`MAPPER`) de [LiveStrategyRunner.java](file:///Volumes/T7/src/trading-bridge/trading-strategies/src/main/java/com/martinfou/trading/strategies/LiveStrategyRunner.java) pour remplacer les valeurs `NaN` et `Infinity` des doubles par `0.0` lors de l'écriture de l'état.
- [ ] Task 3 : Enregistrer les handles de Thread dans RunManager (AC: 3)
  - [ ] Ajouter une structure de correspondance `runnerThreads` (`runId -> Thread`) dans [RunManager.java](file:///Volumes/T7/src/trading-bridge/trading-runtime/src/main/java/com/martinfou/trading/runtime/RunManager.java).
  - [ ] Renseigner le thread courant au début de `executeRun()` et le retirer dans le bloc `finally`.
- [ ] Task 4 : Implémenter le Watchdog de Liveness et l'Alerte de Crash (AC: 3, 4, 5)
  - [ ] Modifier [StaleRunWatchdog.java](file:///Volumes/T7/src/trading-bridge/trading-runtime/src/main/java/com/martinfou/trading/runtime/StaleRunWatchdog.java) pour ajouter une routine de liveness des threads.
  - [ ] Si un run est marqué `RUNNING` mais que son thread n'est plus actif ou absent de la liste, générer un thread dump (avec contrôle de fréquence de 5 min) et marquer le run comme `FAILED`.
  - [ ] Persister un événement `RUN_CRASHED` via `eventStore.append` et diffuser l'alerte sur WebSocket.
- [ ] Task 5 : Ajouter le délai de reprise exponentiel (Exponential Backoff) (AC: 6)
  - [ ] Implémenter un registre de temps de démarrage et d'échecs successifs dans le watchdog pour calculer et imposer le délai exponentiel si un crash survient moins de 30 secondes après le démarrage du run.
- [ ] Task 6 : Tests unitaires de validation JUnit 5 (AC: 7)
  - [ ] Rédiger des tests dans `LivenessWatchdogTest.java` simulant le crash d'un exécuteur de run, la transition automatique vers `FAILED`, la génération du dump et l'application du backoff.

## Dev Notes

- **Scrubbing NaN en Jackson** : Utiliser un sérialiseur Jackson `JsonSerializer<Double>` pour formater de manière sécurisée les doubles lors de l'appel à `writeValue()`.
- **Génération de Thread Dump** : Récupérer les informations de thread via `ManagementFactory.getThreadMXBean().dumpAllThreads(true, true)` pour un dump complet incluant les verrous et moniteurs.
- **WebSocket Broadcast** : Utiliser le gestionnaire de connexions WS existant du Control Plane pour pousser le message d'alerte JSON structuré contenant le type `RUN_CRASHED` aux clients connectés.

### Project Structure Notes

- `LiveStrategyRunner` réside dans le module `trading-strategies`.
- `RunManager` et `StaleRunWatchdog` résident dans le module `trading-runtime`, qui gère les sessions d'exécution et les ponts de communication avec l'UI REST/WS.

### References

- [epics.md](file:///Volumes/T7/src/trading-bridge/_bmad-output/planning-artifacts/epics.md#L2282-2297) (Story 41.6 Definition)
- [LiveStrategyRunner.java](file:///Volumes/T7/src/trading-bridge/trading-strategies/src/main/java/com/martinfou/trading/strategies/LiveStrategyRunner.java#L1014) (saveStateNow method)
- [StaleRunWatchdog.java](file:///Volumes/T7/src/trading-bridge/trading-runtime/src/main/java/com/martinfou/trading/runtime/StaleRunWatchdog.java#L50) (checkStaleRuns method)
- [RunManager.java](file:///Volumes/T7/src/trading-bridge/trading-runtime/src/main/java/com/martinfou/trading/runtime/RunManager.java#L747) (executeRun method)

## Dev Agent Record

### Agent Model Used

gemini-1.5-pro

### Debug Log References

### Completion Notes List

### File List
