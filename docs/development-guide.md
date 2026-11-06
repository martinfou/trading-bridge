# Guide de Développement Global — Trading Bridge

**Date de mise à jour :** 2026-11-05  
**Public cible :** Ingénieurs logiciels, Chercheurs quantitatifs (Quants) et Agents IA de codage

---

## 1. Prérequis Système

| Composant | Version Requise | Vérification |
| :--- | :--- | :--- |
| **Java JDK** | **Java 21** (Eclipse Temurin recommandé) | `java -version` |
| **Maven** | **Maven 3.9+ / 4.x** (ou `./mvnw` inclus) | `./mvnw -version` |
| **Node.js & NPM** | **Node 20+ / NPM 10+** (pour `desktop/` et `dashboard/`) | `node -v && npm -v` |
| **PHP & Composer** | **PHP 8.3+ & Composer 2.x** (pour `dashboard/`) | `php -v && composer -V` |
| **Python** | **Python 3.10+** (pour `scripts/`) | `python3 --version` |
| **Docker & Compose** | **Docker Engine 24+ & Compose v2** | `docker compose version` |
| **SQLite3** | **SQLite 3.40+** | `sqlite3 --version` |

> **Astuce :** Exécutez `./scripts/preflight-check.sh` à la racine du dépôt pour valider automatiquement l'ensemble de votre environnement local.

---

## 2. Démarrage Rapide par Partie

### 2.1 Partie 1 — Backend & Moteur Java (`trading-bridge-java`)
```bash
# Compiler tous les 11 modules et exécuter les tests unitaires
./mvnw clean install

# Compiler rapidement sans les tests
./mvnw clean package -DskipTests

# Lancer le serveur Control Plane (HTTP/WS sur http://localhost:8080)
./start-control-plane.sh

# Lancer le client terminal interactif JLine3 (TUI)
./start-tui.sh

# Exécuter un backtest en ligne de commande
./mvnw exec:java -pl trading-examples \
  -Dexec.mainClass="com.martinfou.trading.examples.RunBacktest" \
  -Dexec.args="--strategy LondonOpenRangeBreakout --symbol EUR_USD --tf H1"
```

### 2.2 Partie 2 — Application de Bureau (`trading-bridge-desktop`)
```bash
# Option A : Lanceur racine (compile le JAR si nécessaire et démarre Electron)
./start-desktop.sh

# Option B : Développement manuel avec rechargement à chaud (HMR)
cd desktop
npm install
npm run electron:dev

# Vérification TypeScript et build de production
npm run build
```

### 2.3 Partie 3 — Console Web (`trading-bridge-dashboard`)
```bash
cd dashboard
composer run setup
composer run dev   # Démarre Laravel sur http://127.0.0.1:8000/control
```

### 2.4 Partie 4 — Scripts, SQX & Recherche (`trading-bridge-scripts`)
```bash
# Vérifier le statut des projets StrategyQuant X
python3 scripts/sqx_ctrl.py status

# Tester la connexion lecture seule au compte OANDA Practice (-004)
python3 scripts/smoke-oanda-readonly.py

# Exécuter les auto-tests du démon de snapshot IBKR
python3 scripts/ibkr_snapshot_push.py --selftest

# Lancer la revue de code adversariale IA sur vos modifications locales
./scripts/agy-review.sh
```

---

## 3. Stratégie de Tests & Validation Qualité

* **Tests Unitaires d'un Module Spécifique** :
  ```bash
  ./mvnw test -pl trading-core
  ./mvnw test -pl trading-backtest
  ./mvnw test -pl trading-parser
  ./mvnw test -pl trading-runtime
  ```
* **Tests Fonctionnels Dashboard Laravel** :
  ```bash
  cd dashboard && composer test
  ```
* **Porte Pré-Déploiement Complète** :
  ```bash
  ./scripts/pre-deploy-gate.sh
  ```

---

## 4. Guides de Développement Spécifiques par Partie

Pour les détails propres à chaque sous-système, consultez :
* **[Guide de Développement — Java Backend](./development-guide-trading-bridge-java.md)**
* **[Guide de Développement — Desktop Electron/Vue](./development-guide-trading-bridge-desktop.md)**
* **[Guide de Développement — Dashboard Laravel](./development-guide-trading-bridge-dashboard.md)**
* **[Guide de Développement — Scripts, SQX & Recherche](./development-guide-trading-bridge-scripts.md)**
