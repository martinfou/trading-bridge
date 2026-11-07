# Guide de Déploiement & Opérations — Trading Bridge

**Date de mise à jour :** 2026-11-05  
**Environnements cibles :** Serveur Linux (`trading-vm`), Conteneurs Docker multi-couloirs, Passerelle IB Gateway 10.50 et Packaging Desktop multiplateforme.

> [!CAUTION]
> **Règle Critique :** L'ancien script `scripts/deploy.sh` a été **définitivement retiré le 2026-10-29** (il quitte immédiatement avec le code d'erreur `2`). Utilisez exclusivement **`scripts/deploy-paper.sh`** et **`scripts/pre-deploy-gate.sh`** pour tout déploiement OANDA Paper.

---

## 1. Architecture des Couloirs de Déploiement (*Execution Lanes*)

La plateforme isole strictement ses environnements d'exécution en **3 couloirs Docker indépendants** afin qu'aucune expérimentation ne puisse impacter les comptes Paper ou Live :

| Couloir | Fichier Compose | Conteneur | Fichier Env & Compte | Persistance Hôte | Rôle Opérationnel |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **1. OANDA Paper** | `docker-compose.yml` | `trading-live` (`TB_LANE=paper`) | `.env.paper` (Compte Practice `-004`) | `./data/state` & `./logs` | Exécution Paper officielle des **4 stratégies `GBP_JPY` H1 ELITE**. |
| **2. OANDA Dev / Rehab** | `docker-compose.yml` ou `docker-compose.dev.yml` | `trading-dev` (`TB_LANE=dev`) | `.env.dev` (Compte Practice `-003`) | `./data/state/dev` ou `./data-dev` | Couloir de quarantaine/réhabilitation (5 stratégies manuelles). Piloté via `./scripts/dev-lane.sh`. |
| **3. IBKR CME Futures Paper** | `docker/docker-compose.futures.yml` | `trading-futures` | Variables hôte (`IBKR_ACCOUNT_ID=DUR967322`, `:4002`) | `./data/state/futures` | Exécution Paper des **3 stratégies `MGC` (Micro Or) M30 ELITE** via `IbkrLiveRunner`. |

---

## 2. Procédure de Déploiement OANDA Paper (`scripts/deploy-paper.sh`)

### 2.1 Vérification Pré-Vol (`scripts/pre-deploy-gate.sh`)
Avant tout déploiement, exécutez la porte de validation à 3 niveaux :
```bash
./scripts/pre-deploy-gate.sh
```
Ce script vérifie séquentiellement :
1. `./mvnw -B -DskipITs test` (intégralité des tests unitaires Java).
2. `docker compose config --quiet` (validité de la configuration Compose).
3. `python3 scripts/smoke-oanda-readonly.py` (connectivité HTTPS vers `api-fxpractice.oanda.com` et devise du compte `CAD`).

### 2.2 Simulation à Blanc (*Dry-Run* par défaut) et Application
```bash
# 1. Prévisualiser les opérations sans modifier les conteneurs (Dry-Run par défaut)
./scripts/deploy-paper.sh

# 2. Exécuter le déploiement atomique
./scripts/deploy-paper.sh --apply
```
**Ce que fait `deploy-paper.sh --apply` automatiquement :**
* Acquiert un verrou exclusif `flock` (`/tmp/trading-bridge-deploy-$(id -u).lock`).
* Exécute `pre-deploy-gate.sh` et archive le tag d'image `trading-bridge-paper:pre-deploy` pour rollback instantané.
* Sauvegarde les fichiers `/tmp/live-strategy-state-*.json` du conteneur actif via `docker cp`.
* Reconstruit l'image Docker multi-stage (`Dockerfile`), recrée le conteneur sans le démarrer (`docker compose up -d --no-start`), restaure l'état des stratégies (en excluant `paper-trading-status.json` pour éviter tout conflit de permissions), puis démarre le conteneur.
* Attend un fichier `/tmp/paper-trading-status.json` frais ($\le 300\text{ s}$) et **réconcilie la somme de `activeTrades` avec l'endpoint OANDA `GET /v3/accounts/{id}/openTrades`**.

---

## 3. Déploiement du Couloir IBKR CME Micro Futures (`trading-futures`)

### 3.1 Démarrage et Santé d'IB Gateway (`10.50`)
```bash
# Démarrer IB Gateway en mode Paper (port 4002 headless ou 7497 bureau)
./scripts/ibkr-gateway-up.sh

# Vérifier l'état de santé du socket TCP
./scripts/ibkr-gateway-health.sh
```
> **Sécurité :** `ibkr-gateway-up.sh` et `docker-entrypoint-ibkr.sh` refusent catégoriquement les ports Live (`4001`, `7496`) tant que le mode Paper est configuré.

### 3.2 Lancement du Conteneur `trading-futures`
```bash
docker compose -f docker/docker-compose.futures.yml up -d --build
docker logs -f trading-futures
```
* Le conteneur exécute `scripts/docker-entrypoint-ibkr.sh` qui supervise `com.martinfou.trading.runtime.ibkr.IbkrLiveRunner` avec reconnexion à backoff exponentiel (`10 s` $\rightarrow$ `3600 s`).
* Les journaux de fills et d'attribution de stratégies (`orderRef`) sont persistés dans `data/state/futures/ibkr-fills-journal.json` afin de survivre à tout redémarrage de conteneur.

---

## 4. Gestion du Couloir de Développement Isolé (`scripts/dev-lane.sh`)

```bash
# Vérifie que .env.dev utilise un compte OANDA distinct de .env.paper, puis démarre trading-dev
./scripts/dev-lane.sh up

# Consulter l'état et les journaux du couloir dev
./scripts/dev-lane.sh status
./scripts/dev-lane.sh logs
```

---

## 5. Maintenance de la Base SQLite & Supervision Continue

* **Purge et Compactage Automatique (`scripts/prune-db.sh`)** :
  * Exécuté automatiquement chaque nuit à **`04:17`** par `docker-entrypoint.sh` (ou manuellement via `./scripts/prune-db.sh`).
  * Supprime par lots de 200 runs les lignes de `trades` et les blobs `equity_curve_json` des backtests de plus de `30` jours (`RETENTION_DAYS=30`) tout en préservant les métriques scalaires dans `backtest_runs`, puis exécute `VACUUM` et `PRAGMA wal_checkpoint(TRUNCATE)`.
* **Démons de Télémétrie Hermes Web (systemd sur `trading-vm`)** :
  * `scripts/ibkr_snapshot_push.py` (`clientId=90`, toutes les 5 min).
  * `scripts/ibkr_pnl_push.py` (`clientId=98`, toutes les heures).
  * `scripts/sqx_telemetry_pusher.py` (toutes les 4 s lors des campagnes d'évolution SQX).
