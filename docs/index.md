# Index de la Documentation - Trading Bridge

Bienvenue sur l'index principal de la documentation technique de **Trading Bridge**. Ce document sert de point d'entrée et de référence de recherche pour les développeurs et les agents IA travaillant sur ce dépôt.

---

## 1. Structure du Projet

Trading Bridge est un projet multi-parties organisé en monorepo :

*   **`trading-bridge-java`** (Backend / Moteur Core) : Moteur d'exécution, backtests, serveur d'API Javalin, gestionnaire de promotion de stratégie et persistance SQLite.
*   **`trading-bridge-desktop`** (Interface Bureau) : Application native Electron + Vue 3 permettant de monitorer les backtests et de piloter le trading.
*   **`trading-bridge-dashboard`** (Console Web) : Panneau d'administration léger en PHP Laravel pour VPS.

---

## 2. Documentation Générée (Deep Scan)

### Général & Architecture
*   **[Présentation Générale](./project-overview.md)** – Résumé exécutif du projet, structure générale et index global.
*   **[Analyse de l'Arborescence](./source-tree-analysis.md)** – Représentation annotée du dépôt et du rôle de chaque module.
*   **[Architecture d'Intégration](./integration-architecture.md)** – Fonctionnement des liaisons inter-processus (IPC, REST, WebSockets).

### Guides & Opérations
*   **[Guide de Développement](./development-guide.md)** – Instructions d'installation locale, compilation, exécution de backtests et de tests.
*   **[Guide de Déploiement](./deployment-guide.md)** – Déploiement en production via Docker Compose et gestion des variables d'environnement.

### Partie Java Backend (`trading-bridge-java`)
*   **[Architecture technique de Java](./architecture-trading-bridge-java.md)** – Conception modulaire Maven et flux d'exécution.
*   **[Contrats d'APIs HTTP/WS](./api-contracts-trading-bridge-java.md)** – Catalogue exhaustif de tous les endpoints Javalin.
*   **[Modèles de Données SQLite](./data-models-trading-bridge-java.md)** – Structures des tables SQLite (`backtest_runs`, `deployments`, `events`, `inspirations`).

### Partie Application Bureau (`trading-bridge-desktop`)
*   **[Architecture technique du Bureau](./architecture-trading-bridge-desktop.md)** – Patron MVVM Vue/Electron et intégration système.
*   **[Inventaire des Composants Vue](./component-inventory-trading-bridge-desktop.md)** – Liste et description de toutes les vues et éléments UI.

### Partie Tableau de Bord Web (`trading-bridge-dashboard`)
*   **[Architecture technique du Tableau de Bord](./architecture-trading-bridge-dashboard.md)** – Patron MVC Laravel et intégration par polling API.
*   **[Contrats Web](./api-contracts-trading-bridge-dashboard.md)** – Description des routes d'accès Web.
*   **[Modèles de Données locaux](./data-models-trading-bridge-dashboard.md)** – Utilisation locale de SQLite par Laravel.
*   **[Inventaire des composants Dashboard](./component-inventory-trading-bridge-dashboard.md)** – Liste et description de la console de contrôle et des éléments Blade.

---

## 3. Documentation de Référence (Existante)

*   **[MISSION_CONTROL.md](./MISSION_CONTROL.md)** – Objectifs opérationnels et étapes clés.
*   **[VISION.md](./VISION.md)** – Feuille de route produit et philosophie de la plateforme.
*   **[contributing.md](./contributing.md)** – Guide d'accueil des contributeurs techniques.
*   **[specs.md](./specs.md)** – Spécifications formelles de l'API de stratégie.
*   **[conversion-guide.md](./conversion-guide.md)** – Guide de portage JForex vers Java.
*   **[sq-xml-format.md](./sq-xml-format.md)** – Structure et tags du format XML StrategyQuant.
*   **[testing.md](./testing.md)** – Stratégie de tests et Golden Baseline.

---

## 4. Démarrage Rapide

### Compiler le Backend Java
```bash
mvn clean install
```

### Lancer l'Application de Bureau
```bash
cd desktop && npm install && npm run electron:dev
```

### Lancer le Dashboard Laravel
```bash
cd dashboard && composer run setup && composer run dev
```
