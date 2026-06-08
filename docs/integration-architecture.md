# Architecture d'Intégration Multi-Parties - Trading Bridge

Ce document décrit comment les différentes parties du projet **Trading Bridge** (le backend Java, l'application de bureau Electron et le tableau de bord Laravel) s'intègrent et communiquent entre elles.

---

## 1. Schéma Général d'Intégration

Les interactions entre les trois composants s'effectuent selon le schéma suivant :

```mermaid
flowchart TD
  subgraph Client Bureau (Electron / Vue)
    desktop_ui["Interface Vue 3"] -- IPC --> electron_main["Processus Electron (Node.js)"]
  end

  subgraph Client Web (Laravel)
    laravel_ui["Tableau de bord HTML"] -- Web Routes --> laravel_ctrl["Contrôleurs PHP"]
  end

  subgraph Serveur Core (Java Runtime)
    control_plane["Plan de Contrôle (Javalin)"]
    event_store["Journal d'Événements (SQLite)"]
    engine["Moteur de Backtest"]
    broker_connectors["Connecteurs Brokers"]
  end

  electron_main -- "Lance & Gère" --> control_plane
  desktop_ui -- "HTTP REST & WebSocket (port 8080)" --> control_plane
  laravel_ctrl -- "HTTP REST (port 8080)" --> control_plane

  control_plane -- "Query / Append" --> event_store
  control_plane -- "Orchestre" --> engine
  control_plane -- "Exécute" --> broker_connectors
```

---

## 2. Intégration de l'Application Bureau (`desktop/`)

L'application Electron gère le cycle de vie du processus Java et communique avec lui en temps réel.

### Gestion du Processus Java (JVM Spawner)
Dans le fichier [`desktop/electron/main.ts`](file:///home/martinfou/dev/src/trading-bridge/desktop/electron/main.ts), Electron est chargé de démarrer et d'arrêter le serveur Java backend :

1.  **Résolution des chemins** :
    *   *En développement* : Localise le JAR shaded compilé dans `trading-runtime/target/` et utilise la variable d'environnement `JAVA_HOME` ou la commande `java` globale.
    *   *En production* : Utilise un JRE minimal embarqué packagé sous `resources/jre/` et le JAR déplacé sous `resources/jar/control-plane.jar`.
2.  **Lancement (Spawn)** :
    *   Exécute le JAR avec les arguments de sécurité et configure les variables d'environnement (`CONTROL_PLANE_PORT=8080`, `TRADING_BRIDGE_DATA_DIR` pour pointer vers le dossier de données de l'application).
3.  **Vérification de la disponibilité (Health Check)** :
    *   Affiche une fenêtre de chargement (Splashscreen) et effectue un *polling* HTTP régulier toutes les 500 ms sur `http://localhost:8080/api/strategies`. Dès que le port répond `200 OK`, la fenêtre principale s'affiche.
4.  **Arrêt (SIGTERM / SIGKILL)** :
    *   Lors de la fermeture d'Electron, un signal `SIGTERM` est envoyé au processus Java. Si le processus ne s'est pas arrêté après 5 secondes, Electron le tue brutalement via un signal `SIGKILL`.

### Communication Front-End / Back-End
*   **Requêtes REST (HTTP)** : Le module Vue utilise le composable `useControlPlane.ts` pour soumettre des exécutions, télécharger des paires ou changer des configurations.
*   **Flux Temps Réel (WebSockets)** : Le composable `useRunWebSocket.ts` ouvre un canal persistant sur `ws://localhost:8080/ws/runs/{runId}` pour intercepter instantanément les exécutions d'ordres (Fills) et les tiques de prix afin de mettre à jour les graphiques dynamiquement.

---

## 3. Intégration du Tableau de Bord Web (`dashboard/`)

Le tableau de bord Laravel s'intègre comme un client HTTP léger et passif.

### Polling et Requêtes Synchrone
Le contrôleur [`ControlRoomController.php`](file:///home/martinfou/dev/src/trading-bridge/dashboard/app/Http/Controllers/ControlRoomController.php) utilise le service [`ControlPlaneClient.php`](file:///home/martinfou/dev/src/trading-bridge/dashboard/app/Services/ControlPlaneClient.php) pour interroger le plan de contrôle Java :

1.  **Visualisation de l'état** :
    *   Lorsqu'un utilisateur accède à `/control`, Laravel effectue des requêtes HTTP GET vers `http://localhost:8080/api/health`, `/control/summary` et `/api/broker-accounts`.
    *   Il agrège les réponses et les transmet à la vue Blade pour un rendu HTML dynamique rafraîchi toutes les 5 secondes.
2.  **Commande de Coupure d'Urgence (Kill Switch)** :
    *   Une action de coupure depuis l'interface Web envoie une requête POST vers le serveur Java : `POST /api/strategies/{strategyId}/kill`.
