# Contrats API - Trading Bridge Java Core

Ce document décrit les points de terminaison (endpoints) HTTP et WebSocket exposés par le plan de contrôle Javalin (`trading-runtime`) de la partie Java.

## Informations générales
* **Port par défaut** : `8080` (modifiable via la configuration)
* **Format d'échange** : JSON (UTF-8)
* **En-têtes CORS** : Activés par défaut pour toutes les origines (`*`)

---

## Points de terminaison HTTP

### 1. Santé et Statut
* **`GET /api/health`**
  * **Description** : Vérifie l'état de fonctionnement du plan de contrôle.
  * **Réponse (200 OK)** :
    ```json
    {
      "status": "ok",
      "version": "1.0.0-SNAPSHOT",
      "dataCatalog": true
    }
    ```

* **`GET /api/sq-bridge/status`**
  * **Description** : Récupère le statut de la passerelle de synchronisation StrategyQuant.
  * **Réponse (200 OK)** : Objet JSON contenant l'état de la file d'attente (inbox).

* **`POST /api/sq-bridge/process-inbox`**
  * **Description** : Déclenche manuellement le traitement du dossier d'importation automatique (`inbox`) de StrategyQuant.
  * **Réponses** :
    * `202 Accepted` : Traitement lancé.
    * `490 Conflict` : Traitement déjà en cours.

* **`GET /api/weekly-builder/status`**
  * **Description** : Récupère le statut de l'outil d'assemblage hebdomadaire (Weekly Builder).

---

### 2. Données Historiques
* **`GET /api/historical-data/status`**
  * **Description** : Récupère le statut des téléchargements et des tâches de données historiques.
  * **Paramètres de requête** : `tf` (optionnel, défaut `h1`) – Unité de temps.

* **`POST /api/historical-data/download`**
  * **Description** : Lance le téléchargement des données historiques pour une paire de devises et une année/période donnée.
  * **Corps de la requête (JSON)** :
    ```json
    {
      "pair": "EUR_USD",
      "year": 2012,
      "tf": "h1",
      "syncMode": false
    }
    ```
  * **Réponses** :
    * `202 Accepted` : Téléchargement initié.
    * `490 Conflict` : Conflit (tâche déjà en cours).

* **`POST /api/historical-data/delete`**
  * **Description** : Supprime un ensemble de données historiques local.
  * **Corps de la requête (JSON)** :
    ```json
    {
      "pair": "EUR_USD",
      "year": 2012,
      "tf": "h1"
    }
    ```

---

### 3. Assemblage Hebdomadaire (Weekly Builder)
* **`POST /api/weekly-builder/plan`**
  * **Description** : Génère le plan pour le portefeuille hebdomadaire.
* **`POST /api/weekly-builder/compile`**
  * **Description** : Compile les stratégies sélectionnées.
* **`POST /api/weekly-builder/deploy`**
  * **Description** : Déploie le portefeuille de stratégies.

---

### 4. Configuration des Comptes Courtiers (Broker)
* **`GET /api/broker-accounts`**
  * **Description** : Liste tous les comptes courtiers configurés (avec les jetons et identifiants masqués).

* **`POST /api/broker-accounts`**
  * **Description** : Enregistre ou fusionne les configurations de comptes courtiers dans le fichier local (`broker-accounts.local.json`).

* **`POST /api/broker-accounts/test`**
  * **Description** : Teste la connexion à un courtier (OANDA ou Interactive Brokers).
  * **Corps de la requête (JSON)** :
    ```json
    {
      "id": "oanda_practice",
      "provider": "OANDA",
      "token": "votre_token",
      "accountId": "votre_account_id"
    }
    ```
  * **Réponse (200 OK)** :
    ```json
    {
      "success": true,
      "balance": 100000.0,
      "currency": "USD"
    }
    ```

---

### 5. Catalogue des Stratégies et Déploiement
* **`GET /api/strategies`**
  * **Description** : Liste les stratégies du catalogue avec leurs statuts de déploiement.

* **`GET /api/strategies/{id}/deployments`**
  * **Description** : Récupère les détails de déploiement pour une stratégie spécifique.

* **`GET /api/strategies/{id}/promote-readiness`**
  * **Description** : Évalue si une stratégie satisfait les critères/portes (gates) requis pour passer à l'étape suivante (ex: démo ou live).

* **`POST /api/strategies/{id}/promote`**
  * **Description** : Promeut une stratégie (ex: passage de Backtest à Paper ou Live).

* **`GET /api/promote-gates/thresholds`**
  * **Description** : Récupère les seuils actuels des portes de promotion.

* **`POST /api/promote-gates/thresholds`**
  * **Description** : Met à jour les seuils des portes de promotion.

* **`POST /api/strategies/{id}/kill`**
  * **Description** : Arrête immédiatement l'exécution en direct ou simulée d'une stratégie (Coupe-circuit / Kill Switch).

---

### 6. Backtests et Exécutions
* **`GET /api/backtests`**
  * **Description** : Liste l'ensemble des backtests enregistrés en base.
  * **Analyses avancées** :
    * `GET /api/backtests/analytics/heatmap` : Renvoie une matrice de sensibilité des paramètres.
    * `GET /api/backtests/analytics/pareto` : Renvoie la frontière de Pareto des backtests.
    * `GET /api/backtests/{runId}` : Détails d'un backtest spécifique.

* **`POST /api/runs`**
  * **Description** : Démarre une nouvelle exécution (simulée ou réelle) de stratégie.
  * **Réponse (202 Accepted)** :
    ```json
    {
      "runId": "uuid-du-run",
      "status": "RUNNING"
    }
    ```

* **`GET /api/runs`**
  * **Description** : Liste les exécutions en cours et passées.

* **`GET /api/runs/{runId}`**
  * **Description** : Récupère l'état complet et la configuration d'un run donné.

* **`GET /api/runs/{runId}/export`**
  * **Description** : Exporte les données du run.
  * **Paramètre de requête** : `format` (`html` pour un rapport visuel ou `ndjson` pour un flux de données).

* **`GET /api/runs/{runId}/trades`**
  * **Description** : Reconstruit la liste des trades clôturés d'un run à partir des ordres exécutés.

* **`GET /api/runs/{runId}/equity-curve`**
  * **Description** : Échantillonne la courbe de capital du run.

* **`GET /api/runs/{runId}/monte-carlo`**
  * **Description** : Effectue une simulation de Monte Carlo sur les trades du run pour évaluer sa robustesse.
  * **Paramètres de requête** : `runs` (défaut `1000`), `blockSize` (défaut `3`).

* **`GET /api/runs/{runId}/bars`**
  * **Description** : Récupère les barres de prix chargées et utilisées par le run.

* **`GET /api/runs/{runId}/events`**
  * **Description** : Récupère l'historique des événements de l'exécution avec pagination.
  * **Paramètres de requête** : `afterSequence` (défaut `0`), `limit` (défaut `100`).

---

## Flux WebSocket (Temps Réel)

### Stream d'événements de Run
* **URL** : `WS /ws/runs/{runId}`
* **Description** : Permet de s'abonner en temps réel aux événements d'exécution (soumission d'ordres, exécution d'ordres/fills, tiques de prix, logs de stratégie, erreurs).
* **Comportement** : Lors de la connexion, le serveur commence par rejouer les 1000 premiers événements stockés pour ce run, puis pousse les nouveaux événements au fur et à mesure de leur émission.
