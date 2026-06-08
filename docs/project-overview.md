# Présentation Générale - Trading Bridge

Bienvenue dans la documentation de **Trading Bridge**, une plateforme complète et automatisée de conversion, d'évaluation et de déploiement de stratégies de trading algorithmique.

---

## 1. Description du Projet et Objectifs

**Trading Bridge** a pour objectif principal de combler le fossé entre les outils d'optimisation de stratégies (comme StrategyQuant) ou de programmation historique (comme JForex) et l'exécution réelle chez des courtiers. 

La plateforme offre trois grandes fonctionnalités :
1.  **Parsing & Traduction** : Compilation et génération automatiques de code Java autonome à partir de fichiers de stratégie exportés au format XML.
2.  **Backtest de Non-Régression (Golden Baseline)** : Moteur de simulation événementielle haute fidélité permettant de valider les performances historiques par rapport à des données réelles de marché (Dukascopy, OANDA).
3.  **Plan de Contrôle et Supervision** : Un serveur centralisé (`trading-runtime`) sécurisant les comptes courtiers, évaluant le passage des filtres de promotion (Gates) et assurant la coupure d'urgence (Kill Switch), pilotable par le biais d'un client terminal (TUI), d'un tableau de bord Web Laravel ou d'une application de bureau native Electron.

---

## 2. Structure du Dépôt et Classification

*   **Structure du dépôt** : Monorepo multi-parties.
*   **Classification d'architecture** : Hybride (Moteur backend Java modulaire orienté services API / Applications clientes découplées).

### Synthèse des Composants du Monorepo

| Composant | Technologie Principale | Rôle | Lien vers l'Architecture |
| :--- | :--- | :--- | :--- |
| **`trading-bridge-java`** | Java 21, Maven 4.x, Javalin, SQLite | Moteur de trading, Plan de contrôle API, persistance, tests de promotion. | [Architecture Java](file:///home/martinfou/dev/src/trading-bridge/docs/architecture-trading-bridge-java.md) |
| **`trading-bridge-desktop`** | Electron, Vue 3, Vite, TS, Charts | Application de bureau pour lancer les backtests locaux et monitorer le live. | [Architecture Desktop](file:///home/martinfou/dev/src/trading-bridge/docs/architecture-trading-bridge-desktop.md) |
| **`trading-bridge-dashboard`** | PHP 8.3, Laravel 13, TailwindCSS | Console d'administration Web distante avec rafraîchissement périodique. | [Architecture Dashboard](file:///home/martinfou/dev/src/trading-bridge/docs/architecture-trading-bridge-dashboard.md) |

---

## 3. Index de la Documentation Technique Développée

Pour explorer en détail chaque aspect de la plateforme, veuillez consulter les guides spécialisés :

### Guides de Prise en Main & Opérations
*   **[`Guide de Développement`](file:///home/martinfou/dev/src/trading-bridge/docs/development-guide.md)** : Étapes d'installation locale, commandes de test, compilation et exécution.
*   **[`Guide de Déploiement et Opérations`](file:///home/martinfou/dev/src/trading-bridge/docs/deployment-guide.md)** : Lancement en production sur VPS avec Docker Compose, et gestion des fichiers d'environnement.

### Architecture & Intégration
*   **[`Analyse de l'Arborescence`](file:///home/martinfou/dev/src/trading-bridge/docs/source-tree-analysis.md)** : Description détaillée du rôle de chaque module Java et structure des fichiers du dépôt.
*   **[`Architecture d'Intégration`](file:///home/martinfou/dev/src/trading-bridge/docs/integration-architecture.md)** : Spécifications des communications inter-processus (IPC Electron, requêtes REST, flux de tiques WebSockets).

### Spécifications Données & APIs
*   **[`Modèles de Données Java`](file:///home/martinfou/dev/src/trading-bridge/docs/data-models-trading-bridge-java.md)** : Modélisation des tables SQLite (`backtest_runs`, `deployments`, `events`, `inspirations`).
*   **[`Contrats d'APIs Java`](file:///home/martinfou/dev/src/trading-bridge/docs/api-contracts-trading-bridge-java.md)** : Catalogue complet des points de terminaison REST et WebSocket.
*   **[`Inventaire des Composants Desktop`](file:///home/martinfou/dev/src/trading-bridge/docs/component-inventory-trading-bridge-desktop.md)** : Descriptif des composants Vue et des interfaces de navigation de l'application de bureau.
