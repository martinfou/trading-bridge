# Inventaire des Composants Desktop - Trading Bridge

Ce document liste et décrit les composants d'interface et les vues de l'application de bureau **Trading Bridge Desktop** (Vue 3 + TypeScript).

---

## 1. Vues Principales (Router Views)

Les vues correspondent aux différentes pages accessibles via le menu de navigation de l'application de bureau (situées sous `desktop/src/views/`) :

*   **`DashboardView.vue`**
    *   *Description* : Tableau de bord général affichant l'état de fonctionnement global de la plateforme, l'activité récente des stratégies et les KPI agrégés.
*   **`StrategiesView.vue`**
    *   *Description* : Catalogue des stratégies de trading disponibles (Custom, StrategyQuant importées, compilées). Affiche des fiches descriptives pour chaque stratégie.
*   **`BacktestHistoryView.vue`**
    *   *Description* : Journal historique de l'ensemble des simulations lancées par l'utilisateur. Permet de filtrer et rechercher les performances passées.
*   **`ResultsView.vue`**
    *   *Description* : Fiche d'analyse détaillée d'un run ou backtest spécifique (courbe de capital, statistiques clés de performance, journaux d'événements).
*   **`CompareView.vue`**
    *   *Description* : Interface comparative permettant de confronter les métriques de plusieurs backtests côte à côte.
*   **`LiveTradingView.vue`**
    *   *Description* : Console de supervision des déploiements réels (Paper ou Live) affichant les connexions courtiers actives, les flux de tiques en direct et les boutons de coupure d'urgence (Kill Switch).
*   **`DataManagerView.vue`**
    *   *Description* : Outil de gestion des données historiques, facilitant le téléchargement de nouveaux flux de prix ou la purge de fichiers périmés.

---

## 2. Composants d'Interface Réutilisables (UI Components)

Ces composants graphiques modulaires sont intégrés au sein des vues (situés sous `desktop/src/components/`) :

*   **`TradeChart.vue`**
    *   *Description* : Graphique financier interactif affichant les bougies de prix japonaises (OHLC) et matérialisant l'ouverture et fermeture des ordres. Basé sur la bibliothèque TradingView *Lightweight Charts*.
*   **`EquityChart.vue`**
    *   *Description* : Graphique de la courbe d'évolution du capital dans le temps suite aux trades clôturés.
*   **`MonteCarloChart.vue`**
    *   *Description* : Graphique de probabilité affichant les scénarios de simulation de Monte Carlo (percentiles de performance et de drawdown).
*   **`ParameterSensitivityHeatmap.vue`**
    *   *Description* : Matrice de chaleur 2D/3D mettant en valeur l'impact de la variation des paramètres de stratégies sur les résultats (ex: Sharpe ou profit total).
*   **`ParetoFrontierChart.vue`**
    *   *Description* : Graphique en nuage de points mettant en évidence la frontière de Pareto des exécutions optimales.
*   **`BacktestForm.vue`**
    *   *Description* : Formulaire de configuration pour lancer de nouveaux backtests (sélection de l'unité de temps, de la période historique, du capital initial et des commissions).
*   **`KpiStrip.vue`**
    *   *Description* : Bandeau horizontal affichant les indicateurs clés de performance d'une stratégie (Facteur de profit, Sharpe, Drawdown, Taux de réussite).
*   **`TradeTable.vue`**
    *   *Description* : Tableau répertoriant la liste des transactions clôturées (heures d'entrée/sortie, prix, volume, P&L, commissions).
*   **`PromoteModal.vue`**
    *   *Description* : Fenêtre modale listant les barrières de validation (seils d'acceptation) et permettant de lancer l'opération de promotion vers un compte simulé/réel.
*   **`StrategyCard.vue`**
    *   *Description* : Fiche d'affichage condensée d'une stratégie pour le catalogue.

---

## 3. Composables de Gestion d'État (Composables)

Situés sous `desktop/src/composables/`, ils gèrent l'état réactif et les requêtes vers le serveur Java :
*   `useControlPlane.ts` : API Client REST.
*   `useRunWebSocket.ts` : Gestionnaire de WebSocket temps réel.
*   `useStatusBar.ts` : Contrôle les messages de la barre de statut système.
