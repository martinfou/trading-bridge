# Architecture - Trading Bridge Desktop App

Ce document présente l'architecture technique détaillée de l'application de bureau **Trading Bridge Desktop**.

---

## 1. Résumé Exécutif (Executive Summary)

**Trading Bridge Desktop** est l'interface utilisateur graphique principale de la plateforme. Elle permet aux traders d'exécuter des backtests, d'analyser la sensibilité des paramètres des stratégies (matrices de chaleur), de visualiser les courbes de capital, de comparer plusieurs exécutions, de gérer le catalogue de données historiques de marché et de superviser le trading en direct.

---

## 2. Pile Technologique (Technology Stack)

*   **Shell Applicatif de Bureau** : Electron ^33.0.0
*   **Framework Front-End** : Vue 3.5.0 (Composition API)
*   **Routing** : Vue Router 4.5.0
*   **Bundler & Dev Server** : Vite 6.0.0
*   **Langage** : TypeScript 5.7.0 / JavaScript (ES6+)
*   **Visualisation des Graphiques Financiers** : Lightweight Charts ^5.0.0 (TradingView)
*   **Bibliothèque d'Icônes** : Lucide Vue ^1.17.0

---

## 3. Patron d'Architecture (Architecture Pattern)

L'application de bureau adopte l'architecture **MVVM (Model-View-ViewModel)** typique des applications Vue 3, couplée au modèle de processus d'Electron :

*   **Processus Principal Electron (Main Process)** : Gère le cycle de vie de la fenêtre native de l'application, l'interception des raccourcis et le démarrage/arrêt automatique du processus Java backend shaded JAR en tâche de fond (JVM Spawner).
*   **Processus de Rendu (Renderer Process)** : Contient l'interface graphique Vue 3, qui s'exécute dans un bac à sable Chromium sécurisé.
*   **Couche de Préchargement (Preload Script)** : Expose un pont d'API IPC minimaliste et sécurisé entre le front-end et le système de fichiers ou le lanceur de processus.

---

## 4. Architecture des Données (Data Architecture)

L'application de bureau n'embarque pas de base de données persistante propre. Elle délègue le stockage des résultats de trading et l'historique au backend Java. 
En production, le processus principal Electron mappe le dossier de persistance du serveur Java sur le répertoire de stockage de l'utilisateur (ex: `app.getPath('userData')/data`).

---

## 5. Design de l'Intégration API

*   **REST HTTP Client** : Intégré via des composables Vue (`useControlPlane.ts`) pour effectuer des requêtes synchrones vers le serveur Java local (`localhost:8080`).
*   **WebSocket Client** : Établit une connexion bidirectionnelle via `useRunWebSocket.ts` vers le canal de diffusion d'événements Java afin de rafraîchir l'interface lors des exécutions d'ordres en temps réel.

---

## 6. Composants Graphiques Clés (Component Overview)

*   `TradeChart.vue` : Affiche le graphique boursier (bougies japonaises OHLC + marqueurs d'achats et de ventes) à l'aide de TradingView Lightweight Charts.
*   `ParameterSensitivityHeatmap.vue` : Visualise la matrice de chaleur des combinaisons de paramètres pour l'optimisation.
*   `ParetoFrontierChart.vue` : Graphique 2D de la frontière de Pareto (ex: P&L vs Drawdown).
*   `PromoteModal.vue` : Interface de validation des portes de promotion d'une stratégie.

---

## 7. Structure des Fichiers (Source Tree)

*   `desktop/electron/` : Processus principal (`main.ts`) et script de préchargement (`preload.ts`).
*   `desktop/src/components/` : Composants graphiques réutilisables (graphiques, formulaires, tables).
*   `desktop/src/views/` : Pages du routeur (Dashboard, Backtests, Strategies, Live).
*   `desktop/src/composables/` : Gestion d'état et clients d'API Java.
*   `desktop/desktop-resources/` : Emplacement du JAR Shaded Java et du JRE pour le packaging de production.

---

## 8. Déploiement et Packaging (Deployment Architecture)

L'application est empaquetée pour une distribution cross-plateforme via `electron-builder` :
*   **Linux** : Génère un AppImage autonome, un paquet Debian (`.deb`) et Arch Linux (`.pacman`).
*   **macOS** : Produit un disque image DMG universel (x64 et Apple Silicon ARM64).
*   **Windows** : Produit un installateur assisté NSIS.

Les binaires compilés de production intègrent directement un environnement d'exécution Java (JRE minimal) construit avec `jlink` pour s'affranchir de toute dépendance Java chez l'utilisateur final.
