# Modèles de Données, Formats Binaires & Journaux d'État — Scripts & Opérations (`trading-bridge-scripts`)

**Date de mise à jour :** 2026-11-06  
**Emplacement :** `config/`, `data/`, `scripts/`

---

## 1. Vue d'Ensemble

La partie **Scripts, Automatisation SQX & Infrastructure (`trading-bridge-scripts`)** manipule 6 formats de fichiers critiques qui assurent la liaison entre StrategyQuant X (`sqcli`), les conteneurs d'exécution Docker (`trading-live`, `trading-dev`, `trading-futures`), les courtiers (OANDA, IBKR) et le portail de supervision externe Hermes Web.

---

## 2. Configuration Déclarative d'Exécution (`config/live-config.json`)

Fichier maître de configuration du portefeuille Paper/Live/Dev lu par `LiveStrategyRunner` et mis à jour par `scripts/sqx_auto_pipeline.py` :

* **Paramètres globaux de risque** :
  * `sizingMode`: `"risk"` (calcul bidirectionnel tenant compte du facteur de conversion devise de cotation $\rightarrow$ devise du compte `CAD`).
  * `defaultRiskPct`: `1.5` (% de la NAV risqué par transaction sur la distance du Stop-Loss).
  * `maxNotionalNavMultiple`: `5` (plafond de levier notionnel global à $5\times$ la NAV).
* **Entrées de stratégies (`strategies[]` — 24 stratégies configurées)** :
  * `id` : Identifiant canonique (ex. `sq-gbpjpy-h1-breakout-4-5-173`, `sq-mgc-m30-breakout-0-24542`).
  * `symbol` : Instrument cible (`GBP_JPY`, `MGC`, `MNQ`, `MES`, `EUR_USD`).
  * `timeframe` : Unité de temps (`H1`, `M30`, `M15`).
  * `lane` : Couloir d'isolation (`"paper"`, `"dev"`, `"live"`).
  * `riskPct` : Budget de risque spécifique (ex. `0.2` % pour `GBP_JPY` H1, `0.5` % pour `MGC` M30).
  * `maxDrawdownDollar` : Coupe-circuit (*Circuit Breaker*) individuel calibré à $1,5\times$ le Max Drawdown historique du backtest.

---

## 3. Journaux d'État et de Réconciliation Courtiers

### 3.1 Journal des Fills IBKR Futures (`data/state/futures/ibkr-fills-journal.json`)
Persisté et rechargé par `IbkrFuturesBroker` / `IbkrLiveRunner` pour garantir qu'aucune clôture de position (SL/TP/EOD) ni attribution de stratégie (`orderRef`) ne soit perdue lors d'un redémarrage du conteneur `trading-futures`.
* Contient les exécutions d'ouverture et de fermeture avec `execId`, `orderRef` (nom de la stratégie sans suffixe `_MGC`), `symbol`, `side`, `qty`, `price`, `realizedPnl` et `timestampUtc`.

### 3.2 État d'Importation P&L IBKR (`~/.local/share/hermes-ibkr/pnl_state.json`)
Maintenu par `scripts/ibkr_pnl_push.py` (permissions `0600`, rétention glissante de 14 jours) :
* Associe chaque `base_exec_id` (identifiant d'exécution IBKR débarrassé du suffixe de révision `.01`/`.02`) au numéro de révision maximal déjà transmis à `POST /api/trading/pnl-import`, garantissant l'idempotence stricte des imports horaires.

### 3.3 Statut Temps Réel OANDA Paper (`/tmp/paper-trading-status.json` & `data/state/live-strategy-state-*.json`)
* `live-strategy-state-<strategyId>.json` : État interne persisté de chaque stratégie active (positions, indicateurs, compteurs de pertes consécutives), sauvegardé et restauré par `scripts/deploy-paper.sh` lors des mises à jour d'image Docker.
* `/tmp/paper-trading-status.json` : Photographie fraîche (`mmin -10` vérifiée par le `HEALTHCHECK` Docker et `deploy-paper.sh`) contenant `activeTrades`, la NAV, le drawdown journalier et l'état de chaque stratégie.

---

## 4. Formats Binaires de Données et d'Archives StrategyQuant X

### 4.1 Cache Binaire de Bougies (`.bars` — `BinaryBarsCache`)
Format binaire compact stocké sous `data/historical/` permettant à `HistoricalDataLoader` de charger 10 à 20 ans de bougies M1/M5/M30/H1 en quelques millisecondes sans surcharge de parsing CSV.

### 4.2 Archives `.sqx` et `dailyEquity.bin` (`scripts/extract_sqx_backtest.py`)
Chaque fichier `.sqx` généré par StrategyQuant X (`databanks/Results/*.sqx`) est une archive ZIP contenant :
* `strategy_Portfolio.xml` & `stats_Portfolio.xml` : Arbre AST XML des règles d'entrée/sortie et statistiques globales.
* `dailyEquity.bin` : Flux binaire Java `ObjectOutputStream` encapsulant des blocs `TC_BLOCKDATA` (`0x77` court / `0x7a` long) qui contiennent un compteur `int32` suivi d'enregistrements `(int32 epoch_second, float32 balance)`. `extract_sqx_backtest.py` décode ce flux, applique le facteur d'échelle Micro (`0.1` sur un capital de base de `10 000 $`), sépare l'échantillon **IS (`< 2023-01-01`)** de l'échantillon **OOS aveugle (`>= 2023-01-01`)** et calcule les métriques institutionnelles ($R^2$, Ulcer Index, Martin Ratio / $UPI$, Sharpe annualisé).

