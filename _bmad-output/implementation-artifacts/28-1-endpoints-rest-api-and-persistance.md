# Story 28.1: Endpoints REST API & Persistance (Multi-Actifs: Forex, Futures & Equities)

Status: ready-for-dev

## Story

En tant que trader,
Je veux démarrer une analyse WFA à distance via des API REST sur n'importe quel actif (Forex, Futures Small/Mid Cap, Actions US) et persister les résultats en base de données SQLite et en fichier JSON,
Afin de pouvoir piloter mes optimisations multi-actifs depuis l'IHM et conserver un historique de mes exécutions.

## Acceptance Criteria

1. **Given** la base de données SQLite connectée au Control Plane
2. **When** j'envoie une requête `POST /api/runs/walk-forward` avec le payload JSON `WfaRunRequest` multi-actifs :
   ```json
   {
     "strategyName": "SmaCrossoverStrategy",
     "symbol": "M2K",
     "assetClass": "FUTURES",
     "timeframe": "H1",
     "startDate": "2025-01-01T00:00:00Z",
     "endDate": "2026-01-01T00:00:00Z",
     "foldCount": 5,
     "isAnchored": false,
     "initialCapital": 10000.0,
     "parameterRanges": [
       {"name": "fastPeriod", "min": 5.0, "max": 20.0, "step": 5.0},
       {"name": "slowPeriod", "min": 20.0, "max": 60.0, "step": 10.0}
     ]
   }
   ```
3. **Then** le champ `assetClass` accepte les valeurs `FOREX`, `FUTURES`, et `EQUITY`, et le système charge le modèle d'évaluation approprié (`AssetValuationModel`).
4. **And** le système démarre l'exécution en arrière-plan via le `WfaManager` et retourne immédiatement un statut HTTP 202 (Accepted) avec l'UUID de la tâche (`{"wfaId": "..."}`).
5. **And** si un calcul WFA est déjà en cours d'exécution, le `WfaManager` renvoie un statut HTTP 409 (Conflict) pour éviter la saturation CPU du serveur de trading.
6. **And** les requêtes `GET /api/runs/walk-forward/{id}` permettent de suivre la progression du calcul (nombre de plis traités, pli en cours, statut `PENDING`, `RUNNING`, `COMPLETED`, `FAILED`).
7. **And** une fois terminé, le résumé de l'exécution est enregistré dans la table SQLite `wfa_runs` (`id`, `strategy_id`, `symbol`, `asset_class`, `is_sharpe`, `oos_sharpe`, `status`, `created_at`, `completed_at`) et le rapport complet JSON est sauvegardé dans `data/reports/wfa/wfa-{id}.json`.
8. **And** le endpoint `GET /api/runs/walk-forward/{id}/report` retourne le contenu du rapport JSON complet avec statut HTTP 200.

## Tasks / Subtasks

- [ ] **Task 1: DTOs & Support Multi-Actifs (`trading-runtime`)** (AC: 2, 3, 4)
  - [ ] Déclarer `AssetClass` (`FOREX`, `FUTURES`, `EQUITY`) dans les DTOs `WfaRunRequest` et `WfaSummaryResponse`.
  - [ ] Valider l'intégrité des paramètres et résoudre le modèle d'évaluation correspondant.
- [ ] **Task 2: Service WfaManager et Concurrency Guard (`trading-runtime`)** (AC: 4, 5, 6)
  - [ ] Implémenter `WfaManager` avec verrouillage de tâche unique pour préserver les ressources CPU.
  - [ ] Intégrer `DataLoader` pour charger l'historique selon la classe d'actif (`data/historical/forex/`, `data/historical/futures/`, `data/historical/stocks/`).
- [ ] **Task 3: Schéma SQLite et persistance `wfa_runs`** (AC: 1, 7)
  - [ ] Mettre à jour la table SQLite `wfa_runs` avec la colonne `asset_class`.
  - [ ] Écrire le rapport JSON complet dans `data/reports/wfa/wfa-{id}.json`.
- [ ] **Task 4: Contrôleur REST API (`trading-runtime`)** (AC: 2, 4, 5, 6, 8)
  - [ ] `POST /api/runs/walk-forward` (202 / 409).
  - [ ] `GET /api/runs/walk-forward/{id}`.
  - [ ] `GET /api/runs/walk-forward/{id}/report`.
- [ ] **Task 5: Tests unitaires et d'intégration** (AC: 1-8)
  - [ ] `WfaManagerTest` sur Forex, Futures (M2K, MES), et Actions (IWM).
