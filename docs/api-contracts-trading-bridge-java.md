# Contrats d'API HTTP & WebSocket — Trading Bridge Java (`trading-bridge-java`)

**Date de mise à jour :** 2026-11-05  
**Serveur :** Javalin `6.7.0` (`com.martinfou.trading.runtime.api.ControlPlaneServer` & `BacktestController`)  
**Port par défaut :** `8080` (`CONTROL_PLANE_PORT`)  
**Protocole :** HTTP/1.1 JSON (UTF-8, dates ISO-8601 UTC) & WebSocket NDJSON

---

## 1. Middleware Global & Garde-Fou de Réconciliation

* **CORS** : Toutes les réponses incluent `Access-Control-Allow-Origin: *` (compatible Electron Desktop, Dashboard Laravel et clients locaux).
* **Verrou de Réconciliation au Démarrage (`503 Service Unavailable`)** : Tant que `runManager.isReconciliationComplete()` est `false` (synchronisation initiale des positions et ordres avec OANDA/IBKR après démarrage ou redémarrage), tout appel mutatif (`POST`, `PUT`, `DELETE`) vers `/api/runs*` ou `/api/strategies*` est bloqué avec :
  ```json
  {
    "error": "Service Unavailable",
    "message": "Reconciliation in progress"
  }
  ```

---

## 2. Santé Système, Diagnostics, Résumé & Sentiment (6 Endpoints)

| # | Méthode | Endpoint | Paramètres (Query / Body) | Code & Structure de Réponse | Description |
| ---: | :--- | :--- | :--- | :--- | :--- |
| 1 | `GET` | `/api/health` | — | `200 {status: "ok", version: "1.0.0-SNAPSHOT", reconciliation: "<STATE>", dataCatalog: true}` | Vérification de disponibilité et état de la réconciliation courtier. |
| 2 | `GET` | `/api/diagnostics/integrity` | — | `200 {status: "OK", errors: []}` ou `500 {status: "CORRUPTED", errors: [...]}` | Exécute `PRAGMA integrity_check` sur la base SQLite `events.db`. |
| 3 | `GET` | `/control/summary` | — | `200 {generatedAt, activeRunsCount, strategiesCount, runs: [...], deployments: [...], signals: {...}, freshness: {...}}` | Synthèse globale du Control Plane pour la TUI et le Dashboard Laravel. |
| 4 | `GET` | `/api/control/summary` | — | Identique à `/control/summary` | Alias standardisé sous `/api` utilisé par l'application Electron Desktop. |
| 5 | `GET` | `/api/drift/comparison` | Query : `strategyId` *(requis)* | `200 DriftEvaluation` (`{strategyId, recommendation, reason, dataSource, evaluatedAt, executionLabel, signals: [...], comparisonMetrics: {...}}`) ou `404` | Évalue la dérive statistique (9 dimensions dont test KS et corrélation de Pearson) entre un run Live/Paper et son backtest de référence. |
| 6 | `GET` | `/api/sentiment/{instrument}` | Path : `instrument` (ex. `EUR_USD`) | `200 {instrument, longPositionPct, shortPositionPct, netSentimentScore, timestamp}` ou `502` | Récupère le carnet de positionnement client OANDA (Long vs Short %). |

---

## 3. Pont StrategyQuant (SQ Bridge) & Weekly Strategy Builder (6 Endpoints)

| # | Méthode | Endpoint | Paramètres | Code & Structure de Réponse | Description |
| ---: | :--- | :--- | :--- | :--- | :--- |
| 7 | `GET` | `/api/sq-bridge/status` | — | `200 {sqHomeConfigured, sqcliReachable, inboxPendingCount, inboxProcessing, lastProbeAt, lastInboxRun}` | État de l'intégration CLI StrategyQuant X (`sqcli`) et de la boîte de réception XML (`data/sq-inbox/pending/`). |
| 8 | `POST` | `/api/sq-bridge/process-inbox` | — | `202` ou `409 {accepted: bool, message: string}` | Déclenche le traitement asynchrone des fichiers XML SQX via `SqInboxProcessor` (`passed/`, `failed/`, `dlq/`). |
| 9 | `GET` | `/api/weekly-builder/status` | — | `200 {pendingCount, compilingCount, compiledCount, deployedBundleCount, failedBundleCount, lastWeekId, templates, validUntil, planProcessing, compileProcessing, deployProcessing, lastPlanRun, lastCompileRun, lastDeployRun}` | État du pipeline hebdomadaire de génération LLM/News (`trading-intelligence`). |
| 10 | `POST` | `/api/weekly-builder/plan` | — | `202` ou `409 {accepted, message}` | Lance le Job 1 (`WeeklyStrategyPlanService`) : analyse macro/calendrier et planification. |
| 11 | `POST` | `/api/weekly-builder/compile` | — | `202` ou `409 {accepted, message}` | Lance le Job 2 (`WeeklyCompileService`) : validation AST (`WeeklyAstValidator`), compilation `JavaCompiler` et smoke backtest. |
| 12 | `POST` | `/api/weekly-builder/deploy` | — | `202` ou `409 {accepted, message}` | Lance le Job 3 (`WeeklyHotSwapService`) : déploiement atomique à chaud dans `data/weekly-strategies/`. |

---

## 4. Studio Évolutionnaire Natif de Génération de Stratégies (`/api/generator/*` — 8 Endpoints)

Géré par `StrategyGeneratorService` (`trading-runtime`) et le moteur `trading-genetics`.

| # | Méthode | Endpoint | Paramètres (Path / Body) | Code & Structure de Réponse | Description |
| ---: | :--- | :--- | :--- | :--- | :--- |
| 13 | `GET` | `/api/generator/status` | — | `200 {running, jobId, state, message, progressPct, currentGeneration, maxGenerations, evaluatedCount, totalToEvaluate, evalSpeedPerSec, passedCount, bestScoreFound, databankCount, totalBarsLoaded, isBarsCount, oosBarsCount, config}` | Progression en direct du moteur de découverte génétique / Monte-Carlo. |
| 14 | `GET` | `/api/generator/databank` | — | `200 {candidates: [DatabankCandidate], status: {...}}` | Liste tous les candidats validés dans la Databank (`data/generator/databank.json`). |
| 15 | `POST` | `/api/generator/start` | Body : `GeneratorRequest` (`symbol`, `timeframe`, `startYear`, `endYear`, `populationSize`, `generations`, `isRatio`, `minTrades`, `maxDrawdownPct`, `minProfitFactor`, `minSharpe`, `initialCapital`) | `202 {jobId, status: "RUNNING"}` ou `409` | Démarre une campagne d'évolution multi-générations sur Virtual Threads Java 21 avec split IS/OOS. |
| 16 | `POST` | `/api/generator/stop` | — | `200 {stopped: true}` | Interrompt proprement la génération en cours. |
| 17 | `POST` | `/api/generator/databank/{id}/promote` | Path : `id` | `200 DatabankCandidate` (incluant `promotedStrategyId`) | Génère le code source `.java` autonome (`JavaStrategyCodeGenerator`) et l'enregistre dans `StrategyCatalog`. |
| 18 | `POST` | `/api/generator/databank/{id}/backtest` | Path : `id` | `202 {runId, strategyId}` | Enregistre le candidat et lance immédiatement un run `BACKTEST` complet. |
| 19 | `DELETE` | `/api/generator/databank/{id}` | Path : `id` | `204 No Content` | Supprime un candidat spécifique de la Databank. |
| 20 | `DELETE` | `/api/generator/databank` | — | `204 No Content` | Vide intégralement la Databank. |

---

## 5. Données Historiques, Univers & Référentiel d'Instruments (9 Endpoints)

| # | Méthode | Endpoint | Paramètres (Query / Path / Body) | Code & Structure de Réponse | Description |
| ---: | :--- | :--- | :--- | :--- | :--- |
| 21 | `GET` | `/api/historical-data/status` | Query : `tf` (défaut `h1` ; supporte `m1`, `m5`, `m15`, `m30`, `h1`, `h4`, `d1`, `w1`) | `200 {status: [{symbol, year, timeframe, csvExists, barsExists, sizeBytes}], activeDownloads: [...], activeTasks: [...]}` | Inventaire des fichiers locaux CSV/`.bars` et progression des téléchargements actifs. |
| 22 | `POST` | `/api/historical-data/download` | Body : `{pair, year, startYear, endYear, tf, syncMode, provider}` | `202` ou `409 {accepted, message}` | Lance un téléchargement asynchrone (`yahoo`, `dukascopy`, `oanda`, `ibkr`). |
| 23 | `POST` | `/api/historical-data/delete` | Body : `{pair, year, tf}` | `200 {success: true, pair, year, tf}` | Supprime les fichiers de données historiques locaux correspondants. |
| 24 | `GET` | `/api/data/symbols` | — | `200 {symbols: ["EUR_USD", "MGC", ...]}` | Liste les symboles disposant de données locales `.bars`. |
| 25 | `GET` | `/api/data/availability/{symbol}` | Path : `symbol` | `200 {symbol, timeframes: {...}, years: [...]}` | Détail des années et unités de temps disponibles pour un symbole. |
| 26 | `GET` | `/api/instruments` | Query : `assetClass` *(optionnel : `FOREX`, `FUTURES`, `EQUITY`)* | `200 {instruments: [InstrumentDefinition]}` | Liste les instruments du référentiel SQLite (`SqliteUniverseStore`). |
| 27 | `GET` | `/api/universe` | — | `200 {instruments: [InstrumentDefinition]}` | Alias retournant l'intégralité de l'univers d'instruments. |
| 28 | `POST` | `/api/instruments` | Body : `InstrumentDefinition` (`symbol`, `name`, `assetClass`, `pointValue`, `tickSize`, `currency`, `providerTicker`) | `201 InstrumentDefinition` | Ajoute ou met à jour un instrument personnalisé dans `SqliteUniverseStore`. |
| 29 | `DELETE` | `/api/instruments/{symbol}` | Path : `symbol` | `204 No Content` (ou `400` si instrument natif pré-amorcé) | Supprime un instrument personnalisé. |

---

## 6. Comptes Courtiers (OANDA & IBKR), Santé & Marges Portefeuille (8 Endpoints)

| # | Méthode | Endpoint | Paramètres | Code & Structure de Réponse | Description |
| ---: | :--- | :--- | :--- | :--- | :--- |
| 30 | `GET` | `/api/broker/health` | — | `200 [{runId, connected, uptimeStart, lastReplyTime, connectionFailures}]` | État de connexion en direct des runs reliés à un courtier. |
| 31 | `GET` | `/api/broker-accounts` | — | `200 {accounts: [{id, label, provider, environment, restUrl, streamUrl, accountIdMasked, tokenMasked, configured, host, port, clientId}]}` | Liste les comptes OANDA et IBKR configurés (jetons masqués). |
| 32 | `POST` | `/api/broker-accounts` | Body : `{accounts: [...]}` | `200 {accounts: [...]}` | Sauvegarde la configuration dans `broker-accounts.local.json`. |
| 33 | `POST` | `/api/broker-accounts/test` | Body : `{id, provider, token, accountId, restUrl, host, port, clientId}` | `200 {success: bool, balance, currency, error}` | Teste en direct la connexion OANDA REST v20 ou socket IBKR TWS/Gateway. |
| 34 | `POST` | `/api/broker-accounts/oanda-accounts` | Body : `{token, restUrl}` | `200 {accounts: [{id, tags}]}` | Découvre automatiquement les identifiants de comptes OANDA associés à un token. |
| 35 | `GET` | `/api/broker-accounts/balances` | — | `200 {balances: [{id, label, provider, environment, configured, connected, balance, equity, currency, error}]}` | Récupère les soldes et la valeur liquidative (NAV) de tous les comptes configurés. |
| 36 | `GET` | `/api/brokers/{brokerId}/account-summary` | Path : `brokerId` | `200 {accountId, provider, currency, netLiquidation, totalCashBalance, settledCash, buyingPower, initMarginReq, maintMarginReq, availableFunds, excessLiquidity, unrealizedPnL, realizedPnL, grossPositionValue, leverage, marginUtilizationPct, pdtRestricted, dayTradesRemaining, timestamp}` | Photographie complète de marge et de conformité PDT d'un compte IBKR ou OANDA. |
| 37 | `GET` | `/api/portfolio/margins` | — | `200 {totalEquity, totalCash, totalInitialMargin, totalMaintenanceMargin, availableFunds, marginUtilizationPct, pdtRestricted, minDayTradesRemaining, brokers: [...], timestamp}` | Agrégation multi-courtiers de l'utilisation de marge et des garde-fous PDT (`SmallAccountMarginGuard`). |

---

## 7. Catalogue de Stratégies, Portes de Promotion & Kill Switch (8 Endpoints)

| # | Méthode | Endpoint | Paramètres | Code & Structure de Réponse | Description |
| ---: | :--- | :--- | :--- | :--- | :--- |
| 38 | `GET` | `/api/strategies` | Query : `assetClass` *(optionnel)* | `200 {strategies: [{id, family, defaultSymbol, type, indicators, description, assetClasses, tradingStyle, timeframeSuitability, recommendedSymbols, complexity, requireBacktest, deployedMode, promotedAt, sourceRunId, executionLabel, brokerAccountId}]}` | Catalogue complet des stratégies enrichi de leur statut de déploiement actif. |
| 39 | `GET` | `/api/strategies/{id}/deployments` | Path : `id` | `200 {strategyId, deployment: {...}}` | Consulte le déploiement actif d'une stratégie dans `SqliteDeploymentStore`. |
| 40 | `DELETE` | `/api/strategies/{id}/deployments` | Path : `id` | `204 No Content` | Supprime l'enregistrement de déploiement d'une stratégie. |
| 41 | `GET` | `/api/strategies/{id}/promote-readiness` | Path : `id` | `200 {strategyId, readyForPaper, readyForLive, sourceRunId, paperChecks: [...], liveChecks: [...]}` | Évalue les portes de promotion sans modifier l'état. |
| 42 | `POST` | `/api/strategies/{id}/promote` | Path : `id` ; Body : `PromoteRequest {targetMode, runId, executionLabel, brokerAccountId, force}` | `200` (promu) ou `422` (rejeté) `{strategyId, targetMode, promoted, checks: [...], deployment}` | Promeut une stratégie vers `PAPER` ou `LIVE` après validation des seuils (ou forçage audité `force=true`). |
| 43 | `GET` | `/api/promote-gates/thresholds` | — | `200 PromoteGateThresholds` (`minTrades`, `maxDrawdownPct`, `minProfitFactor`, `minSharpe`, `minWinRatePct`, `minCalmar`, `requireWfa`, `minWfe`) | Retourne les seuils numériques actifs des portes de promotion. |
| 44 | `POST` | `/api/promote-gates/thresholds` | Body : `PromoteGateThresholds` | `202 PromoteGateThresholds` | Met à jour et persiste les seuils de promotion. |
| 45 | `POST` | `/api/strategies/{id}/kill` | Path : `id` ; Body : `KillRequest {actor, reason}` | `202 {strategyId, stoppedRunIds: [...], liquidatedOrders: [...], message}` | **Kill Switch d'urgence** : stoppe immédiatement tous les runs de la stratégie, annule les ordres en attente et liquide les positions ouvertes chez le courtier. |

---

## 8. Archives Backtests SQLite & Analytique d'Optimisation (`BacktestController` — 6 Endpoints)

| # | Méthode | Endpoint | Paramètres | Code & Structure de Réponse | Description |
| ---: | :--- | :--- | :--- | :--- | :--- |
| 46 | `GET` | `/api/backtests` | Query : `symbol`, `strategyId`/`strategy`, `minSharpe`, `minProfitFactor`, `sortBy`, `sortOrder`, `limit` (20), `offset` (0) | `200 {total, limit, offset, items: [BacktestRunRecord]}` | Recherche paginée et triée dans la table SQLite `backtest_runs`. |
| 47 | `GET` | `/api/backtests/analytics/heatmap` | Query : `strategyId` *(req)*, `xParam` *(req)*, `yParam` *(req)*, `metric` (`sharpeRatio`), `symbol` | `200 {strategyId, xParam, yParam, metric, data: [{x, y, value, runId}]}` | Génère la matrice 2D de sensibilité paramétrique. |
| 48 | `GET` | `/api/backtests/analytics/pareto` | Query : `strategyId` *(req)*, `symbol`, `metricX` (`maxDrawdownPct`), `metricY` (`totalReturnPct`) | `200 {strategyId, metricX, metricY, frontier: [...], runs: [...], allRunsCount}` | Calcule la frontière de Pareto des runs non-dominés. |
| 49 | `GET` | `/api/backtests/{runId}` | Path : `runId` | `200 BacktestRunDetails` (`equityCurve`, `trades`, `parameters`, `metrics`) ou `404` | Détails complets d'un backtest archivé dans SQLite. |
| 50 | `DELETE` | `/api/backtests` | — | `200 {deleted: true}` | Purge tous les backtests archivés. |
| 51 | `DELETE` | `/api/backtests/{runId}` | Path : `runId` | `200 {deleted: true, runId}` ou `404` | Supprime un backtest archivé spécifique. |

---

## 9. Exécutions (`Runs`), Walk-Forward Analysis (WFA), Diagnostics & Flux WebSocket (19 Endpoints)

| # | Méthode | Endpoint | Paramètres (Path / Query / Body) | Code & Structure de Réponse | Description |
| ---: | :--- | :--- | :--- | :--- | :--- |
| 52 | `POST` | `/api/runs` | Body : `StartRunRequest` (`strategyId`, `symbol`, `mode` [`BACKTEST`/`PAPER`/`LIVE`], `capital`, `lotSize`, `barsSource`, `executionProfile`, `fillMode`, `brokerAccountId`, `granularity`, `intervalSec`, `parameters`) | `202 {runId, status: "RUNNING"}` | Démarre un run `BACKTEST`, `PAPER` ou `LIVE` via `RunManager`. |
| 53 | `GET` | `/api/runs` | — | `200 {runs: [...]}` | Liste tous les runs (fusionnant les runs actifs en mémoire, `run_records` et `backtest_runs`). |
| 54 | `POST` | `/api/runs/walk-forward` | Body : `WfaRunRequest` (`strategyId`, `symbol`, `timeframe`, `startYear`, `endYear`, `inSampleDays`, `outOfSampleDays`, `anchored`, `initialCapital`, `parameterRanges`) | `202 {wfaId, status: "RUNNING", message}` ou `409` | Lance une analyse Walk-Forward asynchrone via `WfaManager`. |
| 55 | `GET` | `/api/runs/walk-forward` | Query : `limit` (défaut 50) | `200 {runs: [WfaSummaryResponse]}` | Liste l'historique des analyses WFA depuis `SqliteWfaRunStore`. |
| 56 | `GET` | `/api/runs/walk-forward/{id}` | Path : `id` | `200 WfaProgressResponse` (`wfaId`, `status`, `progressPct`, `currentWindow`, `totalWindows`, `summary`) | Suit l'avancement d'un job WFA et retourne son résumé (WFE, Sharpe OOS). |
| 57 | `GET` | `/api/runs/walk-forward/{id}/report` | Path : `id` | `200` Rapport WFA JSON complet (`reports/wfa/wfa-{id}.json`) ou `404` | Retourne les résultats détaillés pli par pli (IS/OOS) d'une WFA. |
| 58 | `GET` | `/api/runs/{runId}` | Path : `runId` | `200` État complet du run (`runId`, `strategyId`, `symbol`, `mode`, `status`, `configSnapshot`, `result`, `positions`, `pendingOrders`, `indicators`) | Inspecte l'état et la télémétrie en direct d'un run. |
| 59 | `POST` | `/api/runs/{runId}/retire` | Path : `runId` ; Body/Query : `reason` | `202 {runId, status: "RETIRED", reason}` | Arrête proprement et archive un run actif. |
| 60 | `GET` | `/api/runs/{runId}/export` | Path : `runId` ; Query : `format` (`html` ou `ndjson`) | `200 text/html` (`DueDiligenceHtmlExporter`) ou `application/x-ndjson` (`EvidencePackExporter`) | Exporte le rapport HTML de Due Diligence ou le pack de preuves NDJSON. |
| 61 | `GET` | `/api/runs/{runId}/trades` | Path : `runId` | `200 {runId, trades: [Trade]}` | Liste toutes les transactions clôturées d'un run. |
| 62 | `GET` | `/api/runs/{runId}/trades/summary` | Path : `runId` | `200 {runId, totalTrades, winningTrades, losingTrades, winRatePct, totalPnl, averagePnl, profitFactor, maxWin, maxLoss}` | Statistiques agrégées des transactions d'un run. |
| 63 | `GET` | `/api/runs/{runId}/equity-curve` | Path : `runId` | `200 {runId, equityCurve: [{timestamp, equity, drawdownPct}]}` | Série temporelle de la courbe d'équité et du drawdown. |
| 64 | `GET` | `/api/runs/{runId}/weekly-stats` | Path : `runId` | `200 {runId, weeklyStats: [{weekStart, tradesCount, winRatePct, netPnl, profitFactor, sharpeRatio, maxDrawdownPct}]}` | Découpage hebdomadaire ISO des performances (mis en cache avec invalidation sur nouveau `FILL`). |
| 65 | `GET` | `/api/runs/{runId}/alignment` | Path : `runId` | `200 {runId, aligned: bool, anomalies: [{anomalyId, anomalyType, orderId, message, deltaPrice, deltaTimeMs, createdAt}]}` | Anomalies d'alignement et de réconciliation entre exécution Live/Paper et référence (`SqliteTradeAlignmentStore`). |
| 66 | `GET` | `/api/runs/{runId}/monte-carlo` | Path : `runId` ; Query : `runs` (1000), `blockSize` (3) | `200 {runId, simulations, blockSize, p5, p25, p50, p75, p95, worstPnl, bestPnl, var95, probabilityOfLoss, curves: [...]}` | Simulation Monte-Carlo par block-bootstrap sur les trades du run. |
| 67 | `GET` | `/api/runs/{runId}/bars` | Path : `runId` ; Query : `limit`, `to` | `200 {runId, bars: [Bar]}` | Retourne une fenêtre paginée de bougies OHLCV (utilisée pour le défilement arrière de `TradeChart.vue`). |
| 68 | `GET` | `/api/events/{runId}/audit` | Path : `runId` ; Query : `type` *(optionnel)* | `200 {runId, items: [...]}` | Filtre le journal d'événements sur les événements d'audit opérationnel (`BROKER_CONNECT`, `BROKER_DISCONNECT`, `RECONNECT_ATTEMPT`, `RATE_LIMIT_TRIGGERED`, `STALE_PRICE_DETECTED`, `RECONCILIATION_ALERT`, `ERROR`, `OPERATOR_ACTION`). |
| 69 | `GET` | `/api/runs/{runId}/events` | Path : `runId` ; Query : `afterSequence` (0), `limit` (1..1000, défaut 100) | `200 {runId, afterSequence, nextAfterSequence, items: [{sequence, event}]}` | Lecture paginée par curseur séquentiel du journal d'événements SQLite. |
| 70 | `WS` | `/ws/runs/{runId}` | Path : `runId` | Trames NDJSON : `{sequence, event: {type, timestamp, runId, payload}}` | Rejoue jusqu'à 1 000 événements historiques depuis `SqliteEventStore`, puis diffuse en temps réel via `RunEventHub`. |
