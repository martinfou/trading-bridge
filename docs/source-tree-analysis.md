# Analyse de l'Arborescence du Projet - Trading Bridge

Ce document propose une vue d'ensemble annotée de la structure des dossiers et modules du projet multi-parties **Trading Bridge**, mettant en évidence les points d'entrée, les répertoires critiques et les points d'intégration.

---

## Vue d'ensemble du Monorepo

Le dépôt est structuré comme un monorepo contenant :
1. Les modules de l'application Java backend (au niveau de la racine).
2. L'application client de bureau Electron/Vue dans le dossier `desktop/`.
3. Le tableau de bord Web d'administration Laravel dans le dossier `dashboard/`.

```
trading-bridge/ (Racine)
├── pom.xml                       # POM parent Maven (Java 21)
├── trading-core/                 # Modèles de domaine, indicateurs et contrats
├── trading-backtest/             # Moteur de simulation de backtest & métriques
├── trading-data/                 # Client OANDA & chargeur de données historiques
├── trading-broker/               # Connecteurs de courtage en direct (OANDA & IBKR)
├── trading-strategies/           # Catalogue et implémentations de stratégies
├── trading-runtime/              # Serveur API Javalin et gestionnaire de promotion
├── trading-parser/               # Lecteur XML StrategyQuant et générateur de code Java
├── trading-examples/             # CLI de backtest et scripts de test
├── trading-tui/                  # Console client interactive CLI
├── trading-genetics/             # Moteur d'optimisation génétique (hors-ligne)
├── trading-intelligence/         # Base d'inspirations de recherche
│
├── desktop/                      # Application de bureau (Vue 3 + Electron + Vite)
│   ├── src/                      # Code source TypeScript/Vue
│   └── electron/                 # Processus principal et preload d'Electron
│
└── dashboard/                    # Interface d'administration Web (Laravel + Tailwind)
    ├── app/                      # Contrôleurs, services et client API Java
    └── routes/                   # Définitions des routes d'accès Web
```

---

## 1. Description Détaillée des Modules Java (Core / Backend)

### ☕ `trading-core`
*   **Rôle** : Noyau central sans dépendances externes.
*   **Répertoires critiques** :
    *   `src/main/java/com/martinfou/trading/core/` : Contient les classes de domaine (`Bar.java`, `Order.java`, `Trade.java`, `Position.java`) et l'interface `Strategy.java`.
    *   `src/main/java/com/martinfou/trading/core/indicators/` : Centralise les indicateurs techniques réutilisables (`Indicators.java` : SMA, EMA, RSI, ATR).

### ⚙️ `trading-backtest`
*   **Rôle** : Moteur de backtest pour évaluer les stratégies.
*   **Répertoires critiques** :
    *   `.../backtest/` : Classes `BacktestEngine.java` (exécution), `RunContext.java` et calculs statistiques (Monte Carlo).
    *   `.../backtest/persistence/` : Gestion du stockage des runs via SQLite (`SqliteBacktestRunStore.java`).

### 📡 `trading-data`
*   **Rôle** : Accès et téléchargement des données de marché.
*   **Répertoires critiques** :
    *   `.../data/` : Client REST OANDA v3 (`OandaPriceClient.java`) et importateur de séries temporelles (`HistoricalDataLoader.java`).

### 💼 `trading-broker`
*   **Rôle** : Passerelle d'exécution d'ordres vers les brokers réels.
*   **Répertoires critiques** :
    *   `.../broker/` : Interface de courtage unifiée et adaptateurs (OANDA API, Interactive Brokers API).

### 📜 `trading-strategies`
*   **Rôle** : Répertoire de toutes les stratégies prêtes à l'emploi.
*   **Répertoires critiques** :
    *   `.../strategies/` : Code source des stratégies (propriétaires, importées, générées) et registre centralisé `StrategyCatalog.java`.

### 🚀 `trading-runtime` (Point d'Entrée Serveur)
*   **Rôle** : Plan de contrôle de la plateforme (promote-gates, journal d'événements, coupe-circuit).
*   **Répertoires critiques** :
    *   `.../runtime/` : Point d'entrée principal **`ControlPlaneMain.java`**, API HTTP/WebSocket **`ControlPlaneServer.java`**, persistance des déploiements (`SqliteDeploymentStore.java`) et événements (`SqliteEventStore.java`).

### 🔎 `trading-parser`
*   **Rôle** : Traduction des stratégies StrategyQuant vers Java.
*   **Répertoires critiques** :
    *   `.../parser/` : Lecteur de fichiers XML StrategyQuant (`SqXmlParser.java`) et compilateur à la volée vers Java (`SqStrategyCodeGenerator.java`).
    *   `.../parser/bridge/` : Processeur de dossier chaud pour la boîte de réception automatique des fichiers XML (`SqInboxProcessor.java`).

### 💻 `trading-examples` (Point d'Entrée CLI)
*   **Rôle** : Contient le point d'entrée en ligne de commande pour le backtest unifié.
*   **Point d'entrée** : **`RunBacktest.java`** (exécutable via `mvn exec:java`).

### ⌨️ `trading-tui` (Point d'Entrée Client CLI)
*   **Rôle** : Interface de commande interactive en console JLine3.
*   **Point d'entrée** : **`TradingTuiMain.java`** (se connecte à l'API de `trading-runtime`).

---

## 2. Structure de l'Application Bureau (`desktop/`)

*   `desktop/src/main.ts` : Point d'entrée de l'application Vue.
*   `desktop/electron/main.ts` : Processus principal Electron (gère le cycle de vie de la fenêtre et lance automatiquement le serveur Java Shaded JAR en tâche de fond).
*   `desktop/src/components/` : Composants graphiques clés :
    *   `TradeChart.vue` : Graphique boursier interactif basé sur *Lightweight Charts* de TradingView.
    *   `ParameterSensitivityHeatmap.vue` : Matrice de chaleur pour l'analyse de sensibilité.
    *   `PromoteModal.vue` : Boîte de dialogue pour le franchissement des barrières de promotion.
*   `desktop/src/views/` : Vues de navigation (Dashboard, BacktestHistory, LiveTrading).
*   `desktop/src/composables/` :
    *   `useControlPlane.ts` : Encapsule les appels REST HTTP vers le serveur Java.
    *   `useRunWebSocket.ts` : Gère la connexion WebSocket en temps réel pour écouter les ticks et fills.

---

## 3. Structure du Tableau de Bord Web (`dashboard/`)

*   `dashboard/routes/web.php` : Définition des accès HTTP (Redirection `/` et route `/control`).
*   `dashboard/app/Http/Controllers/ControlRoomController.php` : Contrôleur gérant le polling de données Java et l'action de coupure d'urgence (Kill Switch).
*   `dashboard/app/Services/ControlPlaneClient.php` : Client HTTP Laravel qui appelle l'API locale du serveur Java sur le port `8080`.
*   `dashboard/resources/views/control-room.blade.php` : Gabarit d'affichage HTML (Blade + TailwindCSS) pour visualiser l'activité des stratégies en cours d'exécution.
