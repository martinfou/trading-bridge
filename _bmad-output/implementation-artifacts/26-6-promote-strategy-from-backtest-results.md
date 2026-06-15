# Story 26.6: Promouvoir une stratégie depuis l'écran des résultats de backtest

Status: done

## Story

En tant que trader quantitatif,
Je veux pouvoir promouvoir une stratégie directement depuis l'écran des résultats de son backtest,
afin d'accélérer mon workflow de déploiement sans avoir à retourner sur le catalogue des stratégies.

## Critères d'acceptation

1. **Given** un run de backtest complété affiché dans ResultsView
   **When** je clique sur le bouton "Promouvoir" dans les actions d'en-tête
   **Then** le PromoteModal s'ouvre avec l'identifiant de la stratégie pré-rempli.

2. **Given** le PromoteModal ouvert dans ResultsView
   **When** je configure et soumets la promotion et que la stratégie passe les barrières de promotion
   **Then** la stratégie est promue, le modal se ferme et une notification de succès s'affiche.

## Tâches / Sous-tâches

- [x] **Task 1: Intégration du bouton et du composant PromoteModal dans ResultsView.vue**
  - [x] Importer `PromoteModal` dans [ResultsView.vue](file:///Volumes/T7/src/trading-bridge/desktop/src/views/ResultsView.vue).
  - [x] Ajouter une variable réactive `showPromoteModal` pour contrôler l'affichage.
  - [x] Ajouter un bouton "Promouvoir" (Promote) dans l'en-tête `header-actions` de [ResultsView.vue](file:///Volumes/T7/src/trading-bridge/desktop/src/views/ResultsView.vue) à côté du bouton "Re-run".
  - [x] Le bouton "Promouvoir" doit être désactivé ou masqué si le run n'est pas dans l'état `COMPLETED` ou si les résultats (`run.result`) ne sont pas disponibles.
  - [x] Déclarer le composant `<PromoteModal>` dans le template en lui passant `run.strategyId` et `showPromoteModal`.
- [x] **Task 2: Gestion des événements et notification de succès**
  - [x] Gérer l'événement `@close` pour fermer le modal.
  - [x] Gérer l'événement `@promoted` pour afficher un message de succès (par exemple via une alerte ou une notification).
  - [x] S'assurer que le bouton respecte le design existant du projet (style de bouton d'action secondaire ou primaire avec icône appropriée).

### Review Findings
- [x] [Review][Patch] La bannière de succès de promotion insérée dans la chaîne v-if fait disparaître tout l'affichage des résultats [desktop/src/views/ResultsView.vue:238-242]
- [x] [Review][Patch] Risque de fuite de mémoire et d'effacement prématuré des notifications dû à des timers non nettoyés [desktop/src/views/ResultsView.vue:32-37]
- [x] [Review][Patch] Chargement inutile et instanciation du composant PromoteModal dès le chargement de la page [desktop/src/views/ResultsView.vue:441-447]
- [x] [Review][Patch] Message de succès en français dans une interface globalement en anglais [desktop/src/views/ResultsView.vue:31]
- [x] [Review][Patch] Utilisation de couleurs hexadécimales brutes pour la classe .banner.success [desktop/src/views/ResultsView.vue:477]
- [x] [Review][Defer] Double vérification redondante pour le timeframe de la stratégie et rendu incorrect de Candle TF [desktop/src/views/ResultsView.vue:268-280] — deferred, pre-existing
- [x] [Review][Defer] Utilisation de styles inline dans le template HTML [desktop/src/views/ResultsView.vue:276] — deferred, pre-existing

## Notes de développement

- Le composant `PromoteModal.vue` s'occupe de communiquer avec le Control Plane Java sous le capot en appelant `promoteStrategy(...)` depuis `useControlPlane`.
- S'assurer d'importer le modal correctement et de gérer les états de chargement si nécessaire.

## Dev Agent Record

### Plan d'implémentation
1. Modifier [ResultsView.vue](file:///Volumes/T7/src/trading-bridge/desktop/src/views/ResultsView.vue) pour importer `PromoteModal.vue`.
2. Ajouter le bouton "Promote" (Promouvoir) dans la section `.header-actions`.
3. Ajouter le composant `<PromoteModal>` dans le template de `ResultsView.vue`.
4. Tester le comportement et s'assurer que tout compile et fonctionne correctement.

### Debug Log
- Pas de bug rencontré lors de l'implémentation. La compilation via TypeScript et Vite est 100% correcte.
- Revue de code effectuée : résolution des 5 anomalies identifiées (timers, montage paresseux, structure conditionnelle v-else-if, localisation, couleurs).

### Notes de complétion
- Bouton "Promote" ajouté avec succès à côté de "Re-run" sur l'écran des résultats (`ResultsView.vue`).
- Bouton "Promote" également ajouté sur le panneau de résultats du Tableau de bord (`DashboardView.vue`) à côté de "View Full Results →" pour un accès direct dès la fin du backtest.
- Modal de promotion câblé avec l'identifiant de stratégie `run.strategyId` (ou `selectedRun.result.strategyId`) pour la promotion directe.
- Bannière de notification de succès temporaire de 5 secondes affichée lors de la promotion réussie.
- Validation de la compilation effectuée avec succès via `npm run build`.
- Application des correctifs de revue de code (structure conditionnelle corrigée, cleanup de timer, modal paresseux, passage en anglais).

## Liste des fichiers modifiés
- `desktop/src/views/ResultsView.vue`
- `desktop/src/views/DashboardView.vue`

## Log des modifications
- 2026-06-15 : Intégration complète de PromoteModal et du bouton Promote sur ResultsView.vue.
- 2026-06-15 : Résolution des retours de revue de code (application de 5 patches).
- 2026-06-15 : Intégration du bouton Promote directement sur DashboardView.vue à la demande de l'utilisateur pour le mode Paper/Live.
