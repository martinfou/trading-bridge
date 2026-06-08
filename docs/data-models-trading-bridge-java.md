# Modèles de Données - Trading Bridge Java Core

Ce document décrit la structure de la base de données SQLite partagée utilisée par la plateforme Java pour stocker les résultats de backtest, les déploiements, les flux d'événements et les inspirations de recherche.

## Fichier de Base de Données
* **Chemin par défaut** : `{project-root}/data/runtime/events.db` (peut être surchargé par les variables d'environnement `TRADING_BRIDGE_EVENT_STORE` ou `TRADING_BRIDGE_DATA_DIR`).
* **Mode SQLite** : `WAL` (Write-Ahead Logging) activé avec niveau de synchronisation `NORMAL` pour garantir des performances optimales en lecture/écriture concurrentes.

---

## Schéma des Tables SQLite

### 1. Table `backtest_runs`
Stocke les métriques de performance consolidées, les paramètres d'exécution et les résultats généraux de chaque simulation de backtest.

* **Schéma DDL** :
  ```sql
  CREATE TABLE IF NOT EXISTS backtest_runs (
      run_id             TEXT    PRIMARY KEY,
      strategy_id        TEXT    NOT NULL,
      symbol             TEXT    NOT NULL,
      period_start       TEXT    NOT NULL,
      period_end         TEXT    NOT NULL,
      parameters         TEXT    NOT NULL,
      parameter_hash     TEXT    NOT NULL,
      initial_capital    REAL    NOT NULL,
      final_equity       REAL    NOT NULL,
      total_pnl          REAL    NOT NULL,
      total_return_pct   REAL    NOT NULL,
      total_trades       INTEGER NOT NULL,
      winning_trades     INTEGER NOT NULL,
      losing_trades      INTEGER NOT NULL,
      win_rate_pct       REAL    NOT NULL,
      max_drawdown_pct   REAL    NOT NULL,
      avg_trade_pnl      REAL    NOT NULL,
      sharpe_ratio       REAL    NOT NULL,
      sortino_ratio      REAL    NOT NULL,
      profit_factor      REAL    NOT NULL,
      calmar_ratio       REAL    NOT NULL,
      total_commission   REAL    NOT NULL,
      total_slippage     REAL    NOT NULL,
      equity_curve       TEXT    NOT NULL,
      created_at         TEXT    NOT NULL
  );
  ```

* **Index** :
  ```sql
  CREATE INDEX IF NOT EXISTS idx_backtest_runs_strategy_hash 
  ON backtest_runs(strategy_id, parameter_hash);
  ```

* **Description des colonnes clés** :
  * `parameters` : Chaîne JSON contenant les paramètres de configuration appliqués lors du backtest.
  * `parameter_hash` : Hachage SHA-256 ou similaire représentant la combinaison unique des paramètres pour éviter les doublons ou identifier la sensibilité des paramètres.
  * `equity_curve` : Chaîne JSON encodant la série temporelle de l'évolution du capital (P&L cumulé) à chaque transaction.
  * `created_at` / `period_start` / `period_end` : Horodatages stockés sous forme de chaînes de caractères au format ISO-8601 UTC (ex: `2026-06-07T03:59:30Z`).

---

### 2. Table `deployments`
Contient l'état de promotion et de déploiement actif de chaque stratégie (Paper Trading ou Live Trading).

* **Schéma DDL** :
  ```sql
  CREATE TABLE IF NOT EXISTS deployments (
      strategy_id       TEXT PRIMARY KEY,
      mode              TEXT NOT NULL,
      promoted_at       TEXT NOT NULL,
      source_run_id     TEXT,
      checks_json       TEXT NOT NULL,
      execution_label   TEXT NOT NULL DEFAULT 'PAPER_STUB',
      broker_account_id TEXT
  );
  ```

* **Description des colonnes** :
  * `strategy_id` : Identifiant de la stratégie (clé primaire, une seule affectation possible à la fois).
  * `mode` : Type d'exécution active (ex: `BACKTEST`, `PAPER`, `LIVE`).
  * `source_run_id` : Référence au `run_id` d'origine (dans `backtest_runs`) ayant servi de base de validation pour la promotion.
  * `checks_json` : Tableau JSON contenant les résultats des validations des barrières de passage (GateCheckResult : Sharpe minimum, drawdown maximum...).
  * `execution_label` : Étiquette caractérisant le mode d'exécution courtier (ex: `PAPER_STUB`, `LIVE_OANDA`, `LIVE_IBKR`).
  * `broker_account_id` : Identifiant de la configuration de compte de courtage associée.

---

### 3. Table `events`
Table d'archivage (append-only) stockant l'intégralité des événements granulaires produits par les exécutions actives (runs) en mode simulation ou live. Elle sert de source de vérité pour le rejeu WebSocket et l'audit.

* **Schéma DDL** :
  ```sql
  CREATE TABLE IF NOT EXISTS events (
      sequence    INTEGER PRIMARY KEY AUTOINCREMENT,
      run_id      TEXT    NOT NULL,
      json_line   TEXT    NOT NULL,
      created_at  TEXT    NOT NULL
  );
  ```

* **Index** :
  ```sql
  CREATE INDEX IF NOT EXISTS idx_events_run_sequence
  ON events(run_id, sequence);
  ```

* **Description des colonnes** :
  * `sequence` : Identifiant de séquence auto-incrémenté assurant l'ordre chronologique exact de réception des messages.
  * `json_line` : Événement brut sérialisé en JSON (contenant les détails des Fills d'ordres, logs de stratégie, tiques, etc.).
  * `created_at` : Horodatage ISO-8601 de l'insertion de l'événement.

---

### 4. Table `research_inspirations`
Enregistre les idées de recherche de stratégies et les concepts générés par le sous-système d'intelligence artificielle ou saisis manuellement.

* **Schéma DDL** :
  ```sql
  CREATE TABLE IF NOT EXISTS research_inspirations (
      id              TEXT PRIMARY KEY,
      title           TEXT NOT NULL,
      description     TEXT NOT NULL,
      status          TEXT NOT NULL,
      result_status   TEXT,
      strategy_id     TEXT,
      metrics_json    TEXT,
      created_at      TEXT NOT NULL,
      updated_at      TEXT NOT NULL
  );
  ```

* **Description des colonnes** :
  * `status` : État de l'idée (ex: `PENDING`, `INVESTIGATING`, `COMPLETED`, `REJECTED`).
  * `metrics_json` : Métriques cibles associées au concept de recherche.
  * `strategy_id` : Liaison optionnelle vers une stratégie compilée et validée issue de cette inspiration.
