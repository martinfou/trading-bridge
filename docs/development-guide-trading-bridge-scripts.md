# Guide de Développement — Scripts, Automatisation SQX & Recherche (`trading-bridge-scripts`)

**Date de mise à jour :** 2026-11-05  
**Portée :** Scripts Python/Bash (`scripts/`), Harnais de recherche (`run-*.sh`) et Conteneurs Docker (`docker/`)

---

## 1. Bonnes Pratiques et Invariants des Scripts Python/Bash

1. **Zéro Dépendance Fragile pour les Démons de Production** :
   * Les scripts critiques (`smoke-oanda-readonly.py`, `extract_sqx_backtest.py`, `sqx_ctrl.py`, `sqx_telemetry_pusher.py`) privilégient la bibliothèque standard Python (`urllib.request`, `json`, `struct`, `zipfile`, `sqlite3`, `subprocess`) afin de s'exécuter de manière fiable sur macOS et sur `trading-vm` sans conflit d'environnement virtuel.
   * Les démons IBKR (`ibkr_pnl_push.py`, `ibkr_snapshot_push.py`) utilisent `ibapi` avec des identifiants de client dédiés (`clientId=98` et `clientId=90`) pour ne jamais entrer en collision avec `IbkrLiveRunner` (`clientId=11`).
2. **Comportement *Fail-Closed* sur les Données Financières** :
   * Ne jamais remplacer une valeur manquante, une erreur réseau ou la sentinelle IBKR (`1.7976931348623157e308` / `Double.MAX_VALUE`) par `0.0` ou un taux de change `1.0` silencieux. Émettre `None` ou lever une exception explicite (`RuntimeError`).
3. **Gestion des Identifiants (Credentials)** :
   * Ordre de résolution strict : `$TB_ENV_FILE` $\rightarrow$ `<repo>/.env.paper` (ou `.env.dev`) $\rightarrow$ `~/.hermes/.env` (déprécié). Ne jamais coder en dur un token OANDA ou Hermes en dehors des variables d'environnement.

---

## 2. Commandes Utiles par Sous-Système

### 2.1 Pilotage StrategyQuant X (`sqx_ctrl.py` & `sqx_auto_pipeline.py`)
```bash
# Lister les 11 projets institutionnels (.cfx) et le nombre de candidats en Databank
python3 scripts/sqx_ctrl.py list

# Voir les processus sqcli actifs et le classement des meilleures stratégies OOS
python3 scripts/sqx_ctrl.py status
python3 scripts/sqx_ctrl.py top MES_H1_Institutional_Trend

# Démarrer ou arrêter un projet SQX en tâche de fond
python3 scripts/sqx_ctrl.py start MGC_H1_Gold_Breakout
python3 scripts/sqx_ctrl.py stop

# Régénérer les suites de projets .cfx avec les 35-40 blocs orthogonaux et règles anti-overfitting
python3 scripts/create_institutional_project_suite.py
python3 scripts/create_oanda_project_suite.py

# Purger les candidats à profit net <= 0 vers Quarantined_Negatives/
python3 scripts/purge_negative_candidates.py

# Lancer le démon de télémétrie SQX -> Hermes Web (cycle 4s)
python3 scripts/sqx_telemetry_pusher.py

# Exécuter le pipeline de validation de parité Java et mise à jour de config/live-config.json
python3 scripts/sqx_auto_pipeline.py
```

### 2.2 Démons de Télémétrie IBKR (`ibkr_pnl_push.py` & `ibkr_snapshot_push.py`)
```bash
# Exécuter les tests unitaires intégrés (filtrage sentinelles & mode dégradé)
python3 scripts/ibkr_snapshot_push.py --selftest

# Simulation à blanc sans appel HTTP vers Hermes Web
python3 scripts/ibkr_snapshot_push.py --dry-run
python3 scripts/ibkr_pnl_push.py --dry-run
```

### 2.3 Exécution des Harnais de Recherche Quantitative (Études Simons 35e–65e)
```bash
# Exécuter un harnais Java de recherche (compile à la volée et lance sur données réelles)
./run-fxcarryreal.sh        # Étude 47e : Carry FX avec taux directeurs historiques réels
./run-refuge-costs.sh       # Étude 55e : Coûts réels sur paires croisées refuges (GBP_CHF, EUR_CHF)

# Exécuter les sondes Python de microstructure et de saisonnalité
python3 scripts/spread-by-hour.py          # Table des multiplicateurs horaires NY (RealCostModel)
python3 scripts/fade-mid-vs-bid.py         # Étude 64e : Preuve de l'artefact Bid vs Mid à 17h00 ET
python3 scripts/rollover-daycount.py       # Étude 56e : Vérification du triple rollover week-end
```

