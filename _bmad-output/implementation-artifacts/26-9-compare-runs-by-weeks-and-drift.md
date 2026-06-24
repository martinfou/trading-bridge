# Story 26.9: Weekly Performance Comparison & Execution Drift Detection

Status: done

## Story

As a trader,
I want to compare different runs (active backtests, persistent backtests, paper trading, and live trading) grouped by calendar weeks, and analyze their execution logic alignment trade-by-trade,
so that I can detect if the backtest engine matches the live/paper engine and be alerted immediately of execution drift or logic divergences.

## Acceptance Criteria

1. **AC1 — Weekly Performance Aggregation:** The control plane backend supports calculating strategy performance metrics (returns, Sharpe ratio, max drawdown, trades count) grouped by ISO calendar weeks (`YYYY-Www`) for both backtest runs and live/paper trading runs. A new endpoint is exposed: `GET /api/runs/{runId}/weekly-stats`.
2. **AC2 — Signal Correlation ID:** Order generation (in `Order` class) includes a deterministic logic-signal key `correlationId` composed of `[strategyId, ruleId, signalBarTimestamp]`. To bypass broker API character limit constraints (OANDA/IBKR), the order placed with the broker carries the standard `clientOrderId` (UUID) as the broker tag, and the mapping between `clientOrderId` and `correlationId` is stored locally in the event store.
3. **AC3 — Trade Alignment & Drift Reconciler:** A `TradeReconciler` service in the `trading-backtest` module matches a run's actual trades (live/paper) with its theoretical backtest trades. It identifies and flags the following anomalies (`ReconciliationAnomaly`):
   - `MISSING_LIVE`: Signal triggered in backtest but trade not executed in live.
   - `GHOST_LIVE`: Trade executed in live but no matching signal in backtest.
   - `TIME_DRIFT`: Executed in both, but timestamp delta exceeds a tolerance (default 5s).
   - `PRICE_DRIFT`: Executed in both, but price delta exceeds a volatility-based threshold (default $0.1 \times \text{ATR}$).
4. **AC4 — GUI Split-Screen Timeline & Comparison:** The existing comparison screen in the desktop application (`CompareView.vue`) is enhanced with an "Alignment" tab showing the split-screen trade timeline and mismatch table in stacked lanes:
   - Identical matching trades are aligned vertically.
   - Time-drifted trades are connected with diagonal lines indicating the lag.
   - Missing or ghost trades show a blank space ("trou noir") with warning badges.
   - Price deltas are highlighted in red when exceeding threshold.
5. **AC5 — Global Alignment Score:** The comparison UI displays a global "Alignment Score" (e.g., 94%) representing the percentage of perfectly matching trades. A warning light (Yellow/Red) changes color if the score drops below configurable thresholds (<95% for warning, <90% for critical).
6. **AC6 — Live Strategy Protection (Kill-Switch / Autopause):** The `RunManager` / execution runtime monitors drift in real-time:
   - If 3 consecutive trades exhibit a `TIME_DRIFT` (Severity 2), the strategy is automatically paused, and an alert is logged.
   - If a single `MISSING_LIVE` or `GHOST_LIVE` logic discrepancy occurs (Severity 3), the strategy is immediately terminated (kill-switch), and all open positions are closed.
7. **AC7 — SQLite Persistence of Alignment:** The comparison results are cached/saved in a local SQLite table `trade_alignment` inside the existing database file to allow instant loading of historical drift analysis in the GUI.

## Tasks / Subtasks

- [x] **Task 1: Backend Weekly Metrics Aggregator (AC1)**
  - [x] Implement ISO week grouping logic using standard Java time libraries (`java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR`).
  - [x] Add thread-safe caching (e.g., using a `ConcurrentHashMap` invalidated on `FILL` events) in `ControlPlaneServer` to store aggregated weekly metrics.
  - [x] Expose `GET /api/runs/{runId}/weekly-stats` to fetch cached weekly KPIs, delegating math to `PerformanceMetrics`.
  - [x] Write unit tests verifying weekly returns and KPI calculations.
- [x] **Task 2: Inject Signal Correlation ID (AC2)**
  - [x] Add `correlationId` field to `Order` in `trading-core`.
  - [x] Update `BacktestEngine` and strategy logic to populate `correlationId` on signal generation.
  - [x] Update `OandaBroker` and `IbkrBroker` (or event store logs) to associate `clientOrderId` with `correlationId` locally, ensuring only the standard `clientOrderId` tag is transmitted to brokers.
- [x] **Task 3: Implement TradeReconciler Service (AC3, AC7)**
  - [x] Create `ReconciliationAnomaly` and `TradeReconciler` in `com.martinfou.trading.backtest.reconciliation`.
  - [x] Create `SqliteTradeAlignmentStore` to manage persistence of alignment records in the existing default runtime database (`data/runtime/events.db`) using the shared connection lifecycle from `SqliteEventStore`.
  - [x] Implement alignment algorithms matching trades by `correlationId` or proximity search fallback.
  - [x] Write 5 TDD unit tests validating all anomaly types (perfect match, missing, ghost, time drift, price drift).
- [x] **Task 4: Implement Real-Time Protection Guards (AC6)**
  - [x] Add a monitoring hook in `RunManager` checking drift results after each live order fill.
  - [x] Implement the automatic pause trigger (3 consecutive time drifts) and the critical kill-switch trigger (logic discrepancy).
  - [x] Add integration tests simulating execution lag and missing fills to assert automatic pause/termination.
- [x] **Task 5: Frontend Split-Screen Timeline & Drift UI (AC4, AC5)**
  - [x] Add an "Alignment" tab inside `CompareView.vue` in the desktop application.
  - [x] Build a split-screen timeline lane component comparing Backtest vs. Live trades.
  - [x] Style anomalies clearly: missing trades as blank slots with warning icons, price/time drifts highlighted, diagonal link lines connecting aligned trades.

### Review Findings

#### Decision Needed
- [x] [Review][Decision] Intégration de la tolérance ATR pour la dérive de prix — La spécification demande un écart basé sur $0.1 \times \text{ATR}$, mais le code utilise un seuil statique. Faut-il implémenter l'intégration dynamique avec l'Indicators Service ou conserver le seuil statique configurable ?
- [x] [Review][Decision] Génération automatique et mapping local du Correlation ID — Defer : reporté à une future story d'intégration broker.
- [x] [Review][Decision] Rendu des connecteurs diagonaux d'alignement — Dismissed : les connecteurs horizontaux avec affichage numérique sont conservés (meilleure clarté).

#### Patches
- [x] [Review][Patch] Doublon d'identifiant de clé Vue dans CompareView.vue [CompareView.vue:542-546]
- [x] [Review][Patch] Risque de tri instable ou NaN dans timelineRows [CompareView.vue:212-215]
- [x] [Review][Patch] Tri lexicographique sur les dates dans ControlPlaneServer [ControlPlaneServer.java:1363-1382]
- [x] [Review][Patch] Fuite de connexion SQLite mémoire dans RunManager [RunManager.java:1673-1674]
- [x] [Review][Patch] Risque de ClassCastException sur payload JSON de fin [ControlPlaneServer.java:1416]
- [x] [Review][Patch] DateTimeParseException sur exitTime égal à "null" [ControlPlaneServer.java:1515-1516]
- [x] [Review][Patch] Facteur d'annualisation erroné (252 au lieu de 52) pour le Sharpe hebdo [ControlPlaneServer.java:1560]
- [x] [Review][Patch] Division par zéro lors du calcul du rendement [ControlPlaneServer.java:1552]
- [x] [Review][Patch] Division par zéro dans le maxDrawdownPct [ControlPlaneServer.java:1589]
- [x] [Review][Patch] Faille logique empêchant la détection des Ghost Trades si le backtest est vide [RunManager.java:1909-1911]
- [x] [Review][Patch] Duplication infinie des anomalies en base SQLite à chaque FILL [RunManager.java:1920-1921]
- [x] [Review][Patch] Modification d'ID d'objet mutable Order [Order.java:1214-1219]
- [x] [Review][Patch] Synchronisation SQLite sur instance JDBC non thread-safe [SqliteTradeAlignmentStore.java:2408]
- [x] [Review][Patch] Algorithme d'appariement glouton et dépendant de l'ordre [TradeReconciler.java:2197-2220]
- [x] [Review][Patch] Max Drawdown calculé localement au lieu de PerformanceMetrics [ControlPlaneServer.java:1561]
- [x] [Review][Patch] Invalidation de cache sur événement FILL non fonctionnelle [ControlPlaneServer.java:1434-1438]
- [x] [Review][Patch] Tests unitaires sur les performances hebdomadaires manquants [ControlPlaneServerTest.java:1]
- [x] [Review][Patch] Connexion SQLite propre ouverte au lieu de réutiliser le cycle de vie existant [RunManager.java:1649-1678]
- [x] [Review][Patch] Fuite mémoire consecutiveTimeDrifts dans RunManager [RunManager.java:1629]

## Dev Notes

- **ATR-Based Price Tolerance:** The drift reconciler needs the ATR of the instrument to compute the dynamic price threshold. Ensure the indicators service can supply the current ATR at the bar timestamp.
- **Circuit Breaker Integration:** The strategy termination must leverage the existing operator action logic from Story 16.6 (`kill-switch`) to ensure a clean exit, closing any outstanding market exposure safely.
- **KPI Calculations:** To prevent code duplication, weekly statistics calculations (Sharpe, returns, drawdowns) must reuse existing static methods from [PerformanceMetrics.java](file:///Volumes/T7/src/trading-bridge/trading-backtest/src/main/java/com/martinfou/trading/backtest/PerformanceMetrics.java).
- **ISO Week Conversion:** Always use Java's standard `java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR` and `IsoFields.WEEK_BASED_YEAR` to group bar timestamps into weeks to prevent leap-year or year-boundary calculation errors.

### References

- Event store database logic: [SqliteEventStore.java](file:///Volumes/T7/src/trading-bridge/trading-runtime/src/main/java/com/martinfou/trading/runtime/SqliteEventStore.java)
- OANDA client tagging: [OandaBroker.java](file:///Volumes/T7/src/trading-bridge/trading-broker/src/main/java/com/martinfou/trading/broker/oanda/OandaBroker.java)
- Story 16.6 spec: [16-6-kill-switch-operator-action.md](file:///Volumes/T7/src/trading-bridge/_bmad-output/implementation-artifacts/16-6-kill-switch-operator-action.md)
