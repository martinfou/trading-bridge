# Story 41.5: resilience-de-reconciliation-appariement-fallback-et-blocage-d-entrees

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a Martin (opérateur),
I want completed trades reconciled using OANDA transaction history with fallback matching and entry signal gating,
so that final realized P&L is accurate and we prevent new trades when ledger mismatch risks are high.

## Acceptance Criteria

1. **Appariement par Repli (Fallback Matching)** : Lors de la consommation d'une tâche de réconciliation depuis la queue asynchrone, si la transaction OANDA récupérée ne possède pas de tag client (`clientExtensions.tag` est absent ou nul), le worker doit apparier la transaction en comparant l'ID de transaction OANDA avec l'ID local (ou en faisant correspondre l'instrument et les volumes en dernier recours).
2. **Gestion de l'État UNCONFIRMED_RECONCILIATION** : Si l'API OANDA renvoie une erreur temporaire (ex: HTTP 5xx, timeout, ou hôte injoignable) ou si la transaction n'est pas trouvée lors de la réconciliation, le trade correspondant doit être marqué à l'état `UNCONFIRMED_RECONCILIATION` dans le runner local.
3. **Réessais Périodiques de Réconciliation** : Le worker asynchrone doit ré-exécuter périodiquement (ex: toutes les 60 secondes) les tâches de réconciliation marquées `UNCONFIRMED_RECONCILIATION` jusqu'à leur confirmation définitive ou jusqu'à une intervention manuelle (annulation de l'opérateur).
4. **Blocage des Signaux d'Entrée (Entry Gating)** : Lorsque la stratégie courante possède au moins un trade marqué à l'état `UNCONFIRMED_RECONCILIATION` :
   - Le runner de la stratégie (`LiveStrategyRunner`) doit ignorer/rejeter systématiquement tout nouveau signal d'entrée (ordre qui ouvrirait ou augmenterait une position).
   - Les signaux de sortie de position ou d'ajustement de stop-loss/take-profit pour les autres trades actifs doivent rester autorisés et s'exécuter normalement.

## Tasks / Subtasks

- [ ] Task 1 : Implémenter la logique d'appariement de repli (AC: 1)
  - [ ] Modifier le worker asynchrone de réconciliation dans `AsyncReconciliationQueue.java` (ou la classe associée) pour implémenter un comparateur de transaction de repli (instrument, sens et taille) en cas d'absence de `clientExtensions.tag`.
- [ ] Task 2 : Implémenter les statuts et la persistance de l'état UNCONFIRMED_RECONCILIATION (AC: 2, 3)
  - [ ] Ajouter un champ/statut `reconciliationStatus` (ex: enum `ReconciliationStatus` avec les valeurs `CONFIRMED`, `UNCONFIRMED_RECONCILIATION`) dans la structure `ActiveTrade` de [LiveStrategyRunner.java](file:///Volumes/T7/src/trading-bridge/trading-strategies/src/main/java/com/martinfou/trading/strategies/LiveStrategyRunner.java).
  - [ ] Modifier le mécanisme de sauvegarde et de restauration de l'état de `LiveStrategyRunner` (les fichiers JSON sous `/tmp/live-strategy-state-*.json`) pour persister et recharger ce statut après un redémarrage.
- [ ] Task 3 : Ajouter les réessais périodiques du worker de réconciliation (AC: 3)
  - [ ] Mettre à jour le worker asynchrone pour re-soumettre régulièrement à intervalle régulier (ex: 60s) les réconciliations de trades marqués `UNCONFIRMED_RECONCILIATION`.
- [ ] Task 4 : Gérer le filtrage des ordres d'entrée (Entry Gating) dans LiveStrategyRunner (AC: 4)
  - [ ] Modifier la méthode `checkPendingOrders()` dans [LiveStrategyRunner.java](file:///Volumes/T7/src/trading-bridge/trading-strategies/src/main/java/com/martinfou/trading/strategies/LiveStrategyRunner.java) pour vérifier si `hasUnconfirmedReconciliation()` est vrai pour la stratégie courante.
  - [ ] Si c'est le cas, filtrer et bloquer toutes les commandes d'achat ou de vente qui constituent une entrée (c'est-à-dire quand le sens de l'ordre ouvrirait ou aggraverait l'exposition de la stratégie). Laisser passer les ordres de sortie.
- [ ] Task 5 : Rédiger les tests unitaires et d'intégration JUnit 5 (AC: 5)
  - [ ] Créer `ReconciliationFallbackTest.java` (dans le module `trading-strategies` ou `trading-runtime`) pour simuler un scénario de réconciliation sans tag client, un échec temporaire d'API OANDA menant au blocage d'ordres d'entrée, et le déblocage automatique après réussite du retry.

## Dev Notes

- **Critère d'identification d'entrée** : Un ordre est considéré comme une entrée si l'exposition nette sur l'instrument (quantité active cumulée) passe de zéro à une valeur non-nulle, ou si l'ordre augmente la taille d'une exposition existante. Si le but est de fermer ou de réduire le risque d'un trade actif existant, l'ordre est considéré comme une sortie et ne doit pas être bloqué.
- **Réessai avec backoff** : Utiliser un planificateur périodique léger (ex: `ScheduledExecutorService` ou une vérification dans le thread du runner toutes les minutes) pour gérer les tentatives de relance de réconciliation.
- **Persistance résiliente** : Le statut `UNCONFIRMED_RECONCILIATION` doit survivre à un redémarrage complet de l'application JVM. C'est pourquoi le champ doit être sauvegardé dans le fichier JSON d'état de la stratégie.

### Project Structure Notes

- `LiveStrategyRunner` réside dans le module `trading-strategies`. Toutes les modifications liées au filtrage des ordres et au statut d'exécution locale de la stratégie y seront centralisées.
- Les tests unitaires de repli peuvent utiliser des objets mocks (comme un mock d'OANDA `OandaRestClient`) pour simuler des réponses d'API instables ou incomplètes.

### References

- [epics.md](file:///Volumes/T7/src/trading-bridge/_bmad-output/planning-artifacts/epics.md#L2268-2281) (Story 41.5 Definition)
- [LiveStrategyRunner.java](file:///Volumes/T7/src/trading-bridge/trading-strategies/src/main/java/com/martinfou/trading/strategies/LiveStrategyRunner.java#L676) (checkPendingOrders method)
- [LiveStrategyRunner.java](file:///Volumes/T7/src/trading-bridge/trading-strategies/src/main/java/com/martinfou/trading/strategies/LiveStrategyRunner.java#L869) (updatePositions method)

## Dev Agent Record

### Agent Model Used

gemini-1.5-pro

### Debug Log References

### Completion Notes List

### File List
