---
title: 'Correction des stratégies en cours d''exécution dupliquées'
type: 'bugfix'
created: '2026-06-24'
status: 'done'
baseline_commit: '1cb76cf02eb193b37d4d2b867864b2824443905f'
context: []
---

## Intent

**Problem:** Les stratégies restaurées sur OANDA (Paper/Live) sont marquées à tort comme obsolètes (stale) par le watchdog au démarrage car la date du dernier événement (lastEventAt) est récupérée à partir d'anciennes sessions en base de données. De plus, l'arrêt de l'exécuteur OANDA échoue à persister l'événement de fin (RUN_ENDED) car l'accès à la balance de compte (getAccountState().equity()) lève une exception suite à la déconnexion préalable du broker. Par conséquent, lors des démarrages ultérieurs, le système restaure à la fois l'ancien run non terminé et le nouveau run créé en doublon.

**Approach:** 
1. Modifier `ControlSummaryService.resolveLastEventAt` pour filtrer les événements de la base de données et ignorer ceux dont le timestamp est antérieur à la date de démarrage de la session courante (`record.startedAt()`).
2. Wrapper l'appel `broker.getAccountState().equity()` dans un bloc `try-catch` au sein de `OandaStreamingExecutor.emitEnded()` pour tolérer les échecs réseau et de déconnexion, évitant ainsi le blocage de l'écriture de `RUN_ENDED`.

## Boundaries & Constraints

**Always:** S'assurer que chaque run arrêté ou échoué persiste systématiquement un événement terminal (RUN_ENDED ou ERROR) dans l'event store pour éviter les restaurations fantômes.

**Ask First:** Modifier d'autres composants clés de gestion de cycle de vie en dehors de la détection de staleness et de la méthode `emitEnded()`.

**Never:** Désactiver globalement le stale run watchdog ou altérer les sécurités de gestion des positions lors des breaches de drawdown ou de limites de pertes.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Restauration de session | Run restauré possédant des événements passés en base. Le watchdog fait sa vérification 30s après démarrage. | Le run n'est pas détecté comme stale car l'ancien événement est filtré et le calcul utilise `startedAt` (durée < 120s). | N/A |
| Arrêt sur broker déconnecté | Le watchdog ou l'opérateur appelle `stop(runId)` sur un exécuteur OANDA. | L'appel à `broker.getAccountState()` échoue mais l'erreur est capturée. L'événement `RUN_ENDED` est écrit avec la valeur de capital configurée par défaut. | Exception capturée et logguée en warning. |

## Code Map

- `trading-runtime/src/main/java/com/martinfou/trading/runtime/ControlSummaryService.java` -- Résout la date du dernier événement (resolveLastEventAt) pour le dashboard et le watchdog.
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/OandaStreamingExecutor.java` -- Gère le cycle de vie de l'exécuteur OANDA et l'émission des événements de démarrage et de fin.

## Tasks & Acceptance

**Execution:**
- [x] `trading-runtime/src/main/java/com/martinfou/trading/runtime/ControlSummaryService.java` -- Ajouter un filtre temporel sur le timestamp des événements chargés de la base de données par rapport à `record.startedAt()`.
- [x] `trading-runtime/src/main/java/com/martinfou/trading/runtime/OandaStreamingExecutor.java` -- Gère `emitEnded()` avec un bloc `try-catch` autour de la récupération de l'equity du broker, en retombant sur `config.resolvedCapital()`.
- [x] `trading-runtime/src/test/java/com/martinfou/trading/runtime/ControlSummaryServiceTest.java` -- Écrire un test unitaire validant le filtrage des anciens événements lors de la résolution de `lastEventAt` sur un run restauré.
- [x] `trading-runtime/src/test/java/com/martinfou/trading/runtime/StaleRunWatchdogTest.java` -- S'assurer que les tests existants du watchdog compilent et passent correctement après ces modifications.

**Acceptance Criteria:**
- Given un run restauré depuis le stockage avec des événements datant d'une ancienne session, when la vérification d'obsolescence (watchdog) s'exécute, then le run n'est pas marqué comme obsolète (stale) tant que la durée depuis sa date de démarrage actuelle est inférieure à la limite configurée (120 secondes).
- Given un run OANDA en cours d'exécution avec un broker déconnecté, when `stop()` est appelé sur l'exécuteur, then le run s'arrête proprement et l'événement `RUN_ENDED` est enregistré avec succès en base de données.

### Review Findings

- [x] [Review][Patch] Inclusion boundary check in resolveLastEventAt [ControlSummaryService.java:237]
- [x] [Review][Defer] Autodestruction circulaire via les événements de correction FILL [OandaStreamingExecutor.java] — deferred, pre-existing
- [x] [Review][Defer] Appels réseau/BD bloquants sur le thread de flux de ticks [OandaStreamingExecutor.java] — deferred, pre-existing
- [x] [Review][Defer] Race conditions de concurrence et double-clôture [OandaStreamingExecutor.java] — deferred, pre-existing
- [x] [Review][Defer] Désynchronisation de l'état de la stratégie [OandaStreamingExecutor.java] — deferred, pre-existing
- [x] [Review][Defer] Risques de NullPointerException (positions et symboles) [ControlSummaryService.java, OandaStreamingExecutor.java] — deferred, pre-existing
- [x] [Review][Defer] Exceptions non gérées lors du rollover de l'exécuteur OANDA [OandaStreamingExecutor.java] — deferred, pre-existing
- [x] [Review][Defer] Échec de la transition terminale de run lors d'erreurs réseau au Stop [RunManager.java] — deferred, pre-existing
- [x] [Review][Defer] Lectures redondantes dans l'event store [ControlSummaryService.java] — deferred, pre-existing
- [x] [Review][Defer] Violation de la contrainte 'Always' en cas d'exception sur le démarrage [RunManager.java] — deferred, pre-existing
- [x] [Review][Defer] Risque d'inexactitude de l'equity finale [RunManager.java] — deferred, pre-existing
- [x] [Review][Defer] Valeurs entryPrice/stopLoss/takeProfit hardcodées à 0.0 lors de pannes [ControlSummaryService.java] — deferred, pre-existing

## Spec Change Log

## Verification

**Commands:**
- `mvn test -pl trading-runtime` -- expected: BUILD SUCCESS

## Suggested Review Order

**Filtrage du statut Stale**

- Filtrage des événements passés pour ignorer les sessions précédentes lors de la restauration
  [`ControlSummaryService.java:237`](../../trading-runtime/src/main/java/com/martinfou/trading/runtime/ControlSummaryService.java#L237)

**Sécurisation de la fin de session**

- Try-catch lors de l'appel d'equity du broker déconnecté à la fin du run
  [`OandaStreamingExecutor.java:807-821`](../../trading-runtime/src/main/java/com/martinfou/trading/runtime/OandaStreamingExecutor.java#L807-L821)

**Tests unitaires**

- Test unitaire pour valider l'exclusion des événements passés sous leur date de démarrage
  [`ControlSummaryServiceTest.java:190-241`](../../trading-runtime/src/test/java/com/martinfou/trading/runtime/ControlSummaryServiceTest.java#L190-L241)
