# Testing

## Contributor onboarding — what “green” means

| Check | Command | Note |
|-------|---------|------|
| Full rebuild | `mvn clean install` | Required after refactors; stale `target/` causes false compile errors |
| Parser module | `mvn test -pl trading-parser` | Always runs in CI |
| Golden CI subset | `mvn test -pl trading-examples -Dtest=GoldenBacktestTest` (or method filter below if your Maven supports it) | Uses `data/ci/` — does **not** skip |
| Golden full year | same test class, full-year method | **Skips** if `data/historical/` missing locally |

A passing `mvn test` at root with only skips on full-year golden is **not** the same as having reproduced the full baseline locally. See [contributing.md](contributing.md).

## Golden backtest

Integration test `GoldenBacktestTest` validates end-to-end behaviour: historical data load, `StrategyCatalog` (including lot wrapper), `BacktestEngine`, and the `LondonOpenRangeBreakout` prop strategy.

Shared comparison logic lives in `GoldenBacktestBaseline.metricMismatches()` (also used by promote `golden_baseline` gate for return/drawdown tolerance).

### CI mini-dataset (always-on)

Committed under `data/ci/` so CI runs without `data/historical/`.

| Metric | CI subset (Jan 2012 H1) | Tolerance |
|--------|-------------------------|-----------|
| Bars | 744 | exact |
| Trades | 3 | exact |
| Total return % | 0.014 (exact: 0.0137585714285704) | ±1% relative |
| Total PnL | $13.76 (exact: 13.758571428570399) | ±1% relative |
| Max drawdown % | 0.027 (exact: 0.0266378078515083) | ±0.01 pp |

- **File:** `data/ci/EUR_USD_H1_subset.csv`
- **Provenance:** First 744 H1 bars of Dukascopy EUR_USD 2012 CSV (January)
- **Regenerate:** `./scripts/generate-ci-golden-subset.sh` (requires local full 2012 download)
- **Test:** `londonOpenRangeBreakout_ciSubset_matchesMiniGoldenBaseline` — does **not** skip

### Full year (optional local)

| Metric | Baseline | Tolerance |
|--------|----------|-----------|
| Bars (EUR_USD H1 2012) | 8760 | exact |
| Trades | 61 | exact |
| Total return % | 0.14 (exact: 0.1396741071428578) | ±1% relative |
| Total PnL | $139.67 (exact: 139.67410714285776) | ±1% relative |
| Max drawdown % | 0.048 (exact: 0.0475867243182637) | ±0.01 pp |

- **Canonical constants:** `com.martinfou.trading.core.GoldenBacktestBaseline` (shared with promote gates)
- **Captured:** 2026-05-31 (`LondonOpenRangeBreakout`, $100k capital)
- **Initial capital:** $100,000
- **Data:** `data/historical/bars/EUR_USD_H1_2012.bars` or Dukascopy CSV equivalent

The full-year test is disabled via `@EnabledIf` when local historical data is missing (not a silent pass inside the test body).

### Re-capture baseline

```bash
# Print live metrics vs documented constants
mvn -q test-compile exec:java -pl trading-examples \
  -Dexec.classpathScope=test \
  -Dexec.mainClass=com.martinfou.trading.examples.GoldenBaselineCapture

# Human-readable summary
mvn exec:java -pl trading-examples \
  -Dexec.mainClass="com.martinfou.trading.examples.RunBacktest" \
  -Dexec.args="LondonOpenRangeBreakout EUR_USD 2012"
```

Update `GoldenBacktestBaseline.java` and this table when behaviour intentionally changes.

### Run golden test only

```bash
mvn test -pl trading-examples -Dtest=GoldenBacktestTest
```

Optional single-method filter (Maven 4.x; if it fails, use the class command above):

```bash
mvn test -pl trading-examples \
  -Dtest=GoldenBacktestTest#londonOpenRangeBreakout_ciSubset_matchesMiniGoldenBaseline
```

## Build hygiene

If tests fail with `Unresolved compilation problem` or stale class errors, run a full clean build:

```bash
mvn clean install
```

See also `AGENTS.md` → Troubleshooting.

## Historical data formats

All backtest runners should load via `HistoricalDataLoader` (`trading-data`):

| Format | Location | Timestamps |
|--------|----------|------------|
| Dukascopy CSV | `data/historical/dukascopy/` | epoch millis in CSV |
| BarStore `.bars` | `data/historical/bars/` | epoch millis (legacy second-based files still readable) |

`scripts/download-data.sh` writes millis to `.bars`. Re-download or re-convert to migrate old second-based files.

## RunEvent JSONL (schema v1)

Machine-readable run output for TUI, CI, and future Laravel integration.

```bash
mvn exec:java -pl trading-examples \
  -Dexec.mainClass="com.martinfou.trading.examples.RunBacktest" \
  -Dexec.args="LondonOpenRangeBreakout EUR_USD 2012 --json"
```

- **stdout:** one JSON object per line (`RUN_STARTED`, then `RUN_ENDED`)
- **stderr:** load/status messages when `--json` is set
- **schema:** `schemaVersion` = 1, fields documented in `RunEvent` (`trading-backtest/.../events/`)

Example:

```json
{"schemaVersion":1,"type":"RUN_STARTED","timestamp":"2026-05-23T12:00:00Z","runId":"…","strategyId":"LondonOpenRangeBreakout","symbol":"EUR_USD","mode":"BACKTEST","payload":{"barCount":8760,"initialCapital":100000.0}}
{"schemaVersion":1,"type":"RUN_ENDED","timestamp":"2026-05-31T02:00:00Z","runId":"…","strategyId":"LondonOpenRangeBreakout","symbol":"EUR_USD","mode":"BACKTEST","payload":{"totalTrades":61,"totalReturnPct":0.1396741071428578,"finalEquity":100139.67410714286,"maxDrawdownPct":0.0475867243182637}}
```

Tests: `RunEventTest` in `trading-backtest`.

## Platform test strategies

Deterministic scripted strategies in `TestStrategies` exercise backtest and paper (stub) behaviour without historical data or random bars.

| Strategy factory | Purpose |
|------------------|---------|
| `noOp()` | Idle — no orders |
| `buyOnce()` | Single MARKET buy, closed at last bar |
| `buyThenSell()` | Explicit round-trip (close without reversal) |
| `alternatingRoundTrips(n)` | N buy/sell pairs |
| `limitBuy(price, qty)` / `limitBuy(..., maxBars)` | LIMIT entry (multi-bar emit for pending fills) |
| `stopBuy(price, qty)` / `stopBuy(..., maxBars)` | STOP breakout entry |
| `buyWithStopLoss(sl)` / `buyWithTakeProfit(tp)` | Attached exit levels |
| `buyTwiceSameSide()` | Same-side position add |
| `delayedMarketBuy(barIndex, qty)` | Order only on a specific bar |
| `sellOnce()` / `sellThenBuy()` | Short entry and explicit cover |
| `limitSell(price, qty)` / `stopSell(price, qty)` | Short-side pending orders |
| `doubleMarketBuySameBar()` | Two MARKET buys same bar (position add) |
| `buyWithStopLossAndTakeProfit(sl, tp)` | SL priority when both levels touched |
| `sellWithStopLoss(sl)` | Short stop-loss above entry |
| `smaCrossover(fast, slow)` | Trend-following smoke |

Bar fixtures: `TestBars.ohlc(...)` and `TestBars.flat(count, price)` — incrementing UTC timestamps, no random data.

| Test class | Coverage |
|------------|----------|
| `BacktestEngineContractTest` | Fill semantics + accounting invariants |
| `PlatformRobustnessTest` | Parameterized normal + edge scenarios; BACKTEST vs PAPER parity |

Run:

```bash
mvn test -pl trading-backtest -Dtest=PlatformRobustnessTest,BacktestEngineContractTest
```

**LIVE mode (Story 16.5):** `POST /api/runs` with `"mode":"LIVE"` and `"executionLabel":"LIVE_OANDA"` executes on the worker via `BrokerRunExecutor` + `OandaBroker`. The control plane persists events only (ADR-13-07); it does not route orders. Requires OANDA credentials in production; tests inject `FakeBroker` via `RunManager(EventStore, BrokerFactory)`.

`RunContext` with `RunMode.LIVE` in `trading-backtest` still throws `UnsupportedOperationException` — runtime LIVE is a separate path. Covered by `PlatformRobustnessTest.liveMode_throwsUnsupported`.

## TUI workshop (Story 13.6)

JLine3 terminal client in `trading-tui` — thin HTTP consumer of the control plane (no trading logic in the TUI).

**Terminal 1 — control plane:**

```bash
mvn exec:java -pl trading-runtime \
  -Dexec.mainClass=com.martinfou.trading.runtime.ControlPlaneMain
```

**Terminal 2 — TUI:**

```bash
mvn exec:java -pl trading-tui \
  -Dexec.mainClass=com.martinfou.trading.tui.TradingTuiMain
```

Environment: `CONTROL_PLANE_URL` (default `http://localhost:8080`).

Slash commands: `/list`, `/status`, `/backtest <strategyId>`, `/promote <id> PAPER|LIVE`, `/run`, `/events`, `/kill`, `/help`, `/quit`.

Tests: `TuiCommandHandlerTest`.

## Laravel control room (Story 13.7)

Thin Laravel app in `dashboard/` — polls Java control plane (no trading logic in PHP).

**Terminal 1 — control plane:** (same as TUI above)

**Terminal 2 — dashboard:**

```bash
cd dashboard && php artisan serve --port=8000
```

Open http://localhost:8000/control — auto-refresh every 5s (`CONTROL_ROOM_REFRESH_SECONDS`).

Env: `CONTROL_PLANE_URL` (default `http://127.0.0.1:8080`).

Tests: `cd dashboard && php artisan test`

## Promote gates (Story 15.5)

Automated gates before `POST /api/strategies/{id}/promote` → PAPER or LIVE. Failed checks return HTTP 422 with structured `GateCheckResult` entries (`name`, `passed`, `message`, optional `threshold`, `actual`).

### Default thresholds

Loaded from `data/runtime/promote-gates.json` (override path via `TRADING_BRIDGE_PROMOTE_GATES`):

| Gate | Default | Description |
|------|---------|-------------|
| `min_trades` | 1 | Minimum closed trades on source BACKTEST |
| `max_drawdown_pct` | 15.0 | Maximum allowed peak-to-trough drawdown % |
| `min_return_pct` | -50.0 | Floor on total return % |
| `golden_baseline` | ±1% return tolerance | When barsSource is `ci` or `year=2012`, metrics must match documented golden baseline |
| `paper_duration_days` | 30 | LIVE requires ≥30 calendar days on **PAPER_OANDA** (stub excluded) |
| `validation_module` | disabled | Optional SPI hook (Epic 19); enable with `validationModuleEnabled: true` |

When validation is enabled, register modules via `ValidationModules.loadDefault()` (control plane wires OOS holdout when `data/runtime/oos-holdout.json` has `"enabled": true`). If `validationModuleEnabled` is true but every module config has `"enabled": false`, the `validation_module` gate passes with « no module configs active — skipped ».

PAPER promote checks: `backtest_completed`, `golden_baseline`, `min_trades`, `max_drawdown_pct`, `min_return_pct`, optional `validation_module` / `oos_holdout`.

LIVE promote checks: `paper_deployed`, `paper_execution_label` (must be `PAPER_OANDA`, not `PAPER_STUB`), `paper_duration_days`.

### ExecutionLabel (Story 15.6)

Canonical enum in `trading-runtime`: `BACKTEST`, `PAPER_STUB`, `PAPER_OANDA`, `PAPER_IBKR`, `LIVE_OANDA`, `LIVE_IBKR`.

- Runs expose `executionLabel` on `GET /api/runs/{runId}` and in `RUN_STARTED` event payload
- Deployments expose `executionLabel` on `GET /api/strategies/{id}/deployments`
- Evidence export `GET /api/runs/{runId}/export` — first JSONL line is `EVIDENCE_METADATA` with `executionLabel`
- LIVE promote from `PAPER_STUB` returns 422: « stub does not count toward paper period »

Tests: `ExecutionLabelTest`, `EvidencePackExporterTest`, `PromoteServiceTest`, `ControlPlaneServerTest`.

## Paper OANDA (Story 16.3)

Start a run with `executionLabel: PAPER_OANDA` via `POST /api/runs` (or `StartRunRequest`). Orders route through `OandaBroker` to OANDA practice REST; events `ORDER_SUBMITTED`, `FILL`, and `REJECT` are journaled like stub paper.

**Environment variables** (never commit credentials):

| Variable | Required | Description |
|----------|----------|-------------|
| `OANDA_API_KEY` or `OANDA_API_TOKEN` | yes | Practice API token |
| `OANDA_ACCOUNT_ID` | yes | Practice account id |
| `OANDA_REST_URL` | no | Override REST base (default practice host) |

Without credentials, `PAPER_OANDA` start returns 422 / `IllegalArgumentException`.

Promote to PAPER defaults to `PAPER_OANDA` when OANDA credentials are present, otherwise `PAPER_STUB`. Explicit `"executionLabel":"PAPER_OANDA"` on promote requires credentials.

Tests: `StubOandaRestClientTest`, `OandaBrokerTest`, `BrokerRunExecutorTest`, `RunManagerTest`.

## Paper period gate (Story 16.4)

LIVE promote requires ≥30 calendar days on a **PAPER_OANDA** deployment (`paper_duration_days` gate). `PAPER_STUB` time does not count. Re-promoting to PAPER while already on `PAPER_OANDA` preserves the original `promotedAt` (deployment lineage).

Tests: `PromoteServiceTest`, `PromoteGatesTest`.

## Kill switch (Story 16.6)

Emergency halt for broker-backed deployments (`PAPER_OANDA`, `LIVE_OANDA`):

```bash
curl -X POST http://localhost:8080/api/strategies/LondonOpenRangeBreakout/kill \
  -H 'Content-Type: application/json' \
  -d '{"actor":"martin","reason":"manual halt"}'
```

Returns HTTP 202 with `affectedRunIds`. Sets a strategy-level kill flag; worker blocks new orders with REJECT `"Kill switch active"`. Appends `OPERATOR_ACTION` (action=`KILL`, actor, reason) to all RUNNING broker runs. Visible in evidence export JSONL.

Tests: `ControlPlaneServerTest`, `BrokerRunExecutorTest`.

## Broker reconciliation (Story 16.7)

During `PAPER_OANDA` and `LIVE_OANDA` runs, `BrokerRunExecutor` reconciles broker positions against journal-derived FILL state after each bar. Divergence emits `RECONCILIATION_ALERT` with structured `divergences[]` (symbol, side, brokerQuantity, journalQuantity, reason). Skipped for `PAPER_STUB` and BACKTEST.

Tests: `ReconciliationServiceTest`.

## Pre-trade risk (Story 16.8)

`RiskEngine.checkPreTrade()` runs before `Broker.submitOrder` on `PAPER_OANDA` and `LIVE_OANDA` runs. Limits loaded from `data/runtime/risk-limits.json` (override via `TRADING_BRIDGE_RISK_LIMITS`):

| Limit | Default | Description |
|-------|---------|-------------|
| `maxPositionSize` | 1,000,000 | Max units per symbol+side including open position |
| `maxOpenExposure` | 2,000,000 | Max sum of \|qty × price\| across open positions + new order |
| `maxDailyDrawdownPct` | 5.0 | UTC-day peak-to-trough equity drawdown before auto-pause (0 disables) |

Blocked orders emit `REJECT` with `rejectSource: RISK_ENGINE`, `limit`, `threshold`, `actual`. `PAPER_STUB` / BACKTEST skip broker pre-trade (backtest path only).

Tests: `RiskEngineTest`.

## Daily drawdown guard (Story 17.10)

On `PAPER_OANDA` and `LIVE_OANDA` runs, `BrokerRunExecutor` evaluates `RiskEngine.checkDailyDrawdown()` at each bar using broker-reported equity. When drawdown exceeds `maxDailyDrawdownPct`:

- Run transitions to `PAUSED` (`RunTransition.PAUSE`)
- `OPERATOR_ACTION` with action=`DAILY_DD_BREACH`, actor=`RISK_ENGINE`
- New orders blocked for remainder of run (`ordersDailyDdBlocked` on `RUN_ENDED`)
- Control summary exposes `dailyDrawdownPct`, `maxDailyDrawdownPct`, `dailyDdBreached` per run

Tests: `RiskEngineTest.checkDailyDrawdown_*`, `RiskEngineTest.brokerRunExecutor_dailyDrawdownPausesAndBlocksOrders`.

## Control summary (Story 17.9)

Prop-shop control room read model:

```bash
curl http://localhost:8080/control/summary
```

Returns `schemaVersion: 1`, global `freshness` (`staleThresholdSeconds`, `staleRunCount`, `lastEventAt`, `secondsSinceLastEvent`), `executionLabelCatalog`, `runs[]` (with `executionLabel`, `executionLabelMeta`, `isStale`, `gaps`, `latestEvent`, optional daily DD metrics), and `signals.gaps` / `signals.drift[]` / `signals.stale[]`. Drift signals are broker-only (Story 17.12): `PAPER_STUB` and backtest-only history yield `HOLD` with `dataSource: INSUFFICIENT`. Runs sorted stale/gaps first.

Broker runs emit `HEARTBEAT` events once per bar (Story 13.8). Stale detection flags `RUNNING` runs with no events for longer than `runningStaleThresholdSeconds` (default **120**; override via `data/runtime/stale-thresholds.json` or `TRADING_BRIDGE_STALE_THRESHOLDS`). Set the threshold above your bar interval for hourly strategies.

Tests: `ControlSummaryServiceTest`, `StaleThresholdsTest`, `BrokerRunExecutorTest`, `ControlPlaneServerTest`.

## Execution label UI metadata (Story 17.11)

`executionLabelMeta` on runs, deployments, strategies list, and evidence export shares one catalog from `ExecutionLabelCatalog`:

| Label | Display | Category | Badge color |
|-------|---------|----------|-------------|
| `BACKTEST` | Backtest | SIMULATION | slate |
| `PAPER_STUB` | Paper (stub) | PAPER_STUB | amber (`stubWarning: true`) |
| `PAPER_OANDA` | Paper OANDA | BROKER_PAPER | blue |
| `PAPER_IBKR` | Paper IBKR | BROKER_PAPER | purple |
| `LIVE_OANDA` | Live OANDA | BROKER_LIVE | red |
| `LIVE_IBKR` | Live IBKR | BROKER_LIVE | dark red |

`GET /control/summary` includes top-level `executionLabelCatalog` for dashboard clients. HTML due diligence reports render the same badge colors inline.

Tests: `ExecutionLabelCatalogTest`, `EvidencePackExporterTest`, `DueDiligenceHtmlExporterTest`.

## Drift signals post-broker (Story 17.12)

`DriftEngine` evaluates FR-15 drift only when broker execution history exists (`PAPER_OANDA`, `PAPER_IBKR`, `LIVE_*`). `BACKTEST` / `PAPER_STUB` alone → `signals.drift[]` entry with `recommendation: HOLD`, `dataSource: INSUFFICIENT`.

Minimum observation: **14 days** or **20 trades** before broker drift metrics apply. Baseline = promote source backtest (`DeploymentRecord.sourceRunId`). Thresholds: `data/runtime/drift-thresholds.json`.

Composite rule: 1 red dimension → `REVIEW_PARAMS`; 2+ → `PAUSE`.

Tests: `DriftEngineTest`, `ControlSummaryServiceTest.buildSummary_stubDeployment_*`.

## OOS holdout gate (Story 19.4)

Locked out-of-sample holdout runs once on the trailing bar window (default **20%**, min **50** bars) with frozen strategy parameters. Parameters must never be tuned on the holdout slice.

Config: `data/runtime/oos-holdout.json` (override via `TRADING_BRIDGE_OOS_HOLDOUT`). Requires `enabled: true` **and** `validationModuleEnabled: true` in `promote-gates.json`.

On promote to PAPER, gate `oos_holdout` compares holdout `maxDrawdownPct` / `totalReturnPct` against configured thresholds. Result is journaled as `OPERATOR_ACTION` with `validationType: OOS_HOLDOUT` and immutable `validationConfigSnapshot` (holdout window + source config hash).

**MVP limitation (decision 1C):** the source BACKTEST run used for promote gates (`golden_baseline`, `min_trades`, etc.) still spans the full bar window including the holdout slice. The holdout gate evaluates the locked tail once with frozen parameters but does not retroactively strip holdout bars from source run metrics. For strict IS/OOS separation, promote from an in-sample-only backtest (future enhancement).

**MVP limitation (decision 2A):** if the strategy produces zero trades on the holdout window, return and drawdown metrics are zero and the gate may pass. Treat as acceptable for MVP; tighten with a `minHoldoutTrades` threshold in a future story.

Validation audit events (`OOS_HOLDOUT`, `EXECUTION_STRESS`) are journaled only after **all** promote gates pass — rejected promotes leave no validation events on the run.

Tests: `BarHoldoutSplitTest`, `OosHoldoutValidationModuleTest`.

## Execution stress gate (Story 19.5)

Re-runs the full backtest bar window with **degraded slippage and commission** via shared `BacktestExecutionCost` (Story 13.9). Default stress: slippage ×3, commission ×2 (baseline defaults 0.01% / $5 when run had zero costs).

Config: `data/runtime/execution-stress.json` (override via `TRADING_BRIDGE_EXECUTION_STRESS`). Requires `enabled: true` and `validationModuleEnabled: true` in promote gates.

Gate `execution_stress` vetoes promote when stressed `maxDrawdownPct` or `totalReturnPct` breach configured thresholds. Result journaled as `OPERATOR_ACTION` with `validationType: EXECUTION_STRESS`.

CI scenario: `ExecutionStressValidationModuleTest.evaluate_ciDeterministicScenario_*` on `sample` 500 bars.

Tests: `ExecutionStressConfigTest`, `ExecutionStressValidationModuleTest`.

## HTML due diligence export (Story 15.7)

Self-contained HTML report for external submission:

```bash
curl "http://localhost:8080/api/runs/{runId}/export?format=html" -o report.html
```

Default export (no `format` param) remains JSONL evidence pack. HTML includes execution-mode disclaimer, config hash, equity summary, Sharpe/PF/max DD (when in RUN_ENDED), and fill-level trade table for broker runs. No CDN dependencies — opens offline.

Tests: `DueDiligenceHtmlExporterTest`, `ControlPlaneServerTest.export_html_*`.

## Prop-shop runbook (Story 15.8)

Operational ritual for paper → LIVE promotion. Full checklist: `docs/prop-shop-runbook.md`.

```bash
curl http://localhost:8080/api/strategies/LondonOpenRangeBreakout/promote-readiness
```

Returns `schemaVersion: 1`, `targetMode`, `ready`, `gates[]`, `paperElapsedDays` / `paperDaysRequired` (when targeting LIVE), `reconciliation`, `killSwitchActive`. **PAPER_STUB never counts** toward the 30-day paper period.

Tests: `PromoteReadinessServiceTest`, `ControlPlaneServerTest.promoteReadiness_*`.

## Multi-account broker routing (Story 16.9)

Configure accounts in `data/runtime/broker-accounts.json`; credentials via per-account env vars (never in JSON). Deployments and runs carry `brokerAccountId`; cross-account routing is blocked.

```bash
curl http://localhost:8080/api/broker-accounts
```

Promote with account: `{"targetMode":"PAPER","executionLabel":"PAPER_OANDA","brokerAccountId":"firm-a",...}`.

Tests: `DeploymentStoreTest`, `BrokerAccountRegistryTest`, `BrokerAccountRoutingTest`.

## IBKR broker execution (Story 16.10)

`IbkrBroker` implements the shared `Broker` interface via TWS / IB Gateway. Set `IBKR_USE_STUB=true` for tests; otherwise TCP connect validates gateway reachability on port 7497 (paper) or 7496 (live).

Environment:

| Variable | Purpose |
|----------|---------|
| `IBKR_GATEWAY_HOST` | Gateway host (default `127.0.0.1`) |
| `IBKR_GATEWAY_PORT` | Override port |
| `IBKR_CLIENT_ID` | TWS client id (default `1`) |
| `IBKR_ACCOUNT_ID` | IB account id |
| `IBKR_USE_STUB` | `true` → in-memory stub (tests) |

Start runs with `executionLabel` `PAPER_IBKR` or `LIVE_IBKR`. Promote to `PAPER_IBKR` is supported (requires IBKR credentials or `IBKR_USE_STUB=true`).

**MVP limitation (decision 1A):** `PAPER_IBKR` does **not** count toward the 30-day paper period required for LIVE promote (`countsTowardPaperPeriod` is true only for `PAPER_OANDA`). LIVE promote always resolves to `LIVE_OANDA` today. Use IBKR paper for connector validation; use `PAPER_OANDA` when targeting the LIVE gate path.

Tests: `IbkrBrokerTest`, `BrokerRunExecutorTest.brokerRunExecutor_ibkr_*`, `OandaBrokerTest` (OANDA independent).

## Paper mode (stub)

Story 12.6 — `RunContexts.paper(...)` (composition layer) replays historical bars with `PaperExecutor` (delegates to `BacktestEngine`). Events use `"mode":"PAPER"`. See `docs/README.md` for CLI examples. Tests: `PaperExecutorTest`.
