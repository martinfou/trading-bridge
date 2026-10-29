# Tech Spec — IBKR Live Futures Lane (the missing runner)

> **Status**: DRAFT — awaiting Martin's approval on the 4 open decisions in §10
> **Audience**: a powerful external coding agent (Claude Code, Codex, agy, or equivalent) with **no session memory**
> **Author**: Hermes (2026-10-29), from a live audit of the running stack
> **Supersedes nothing.** This document **extends** Epic 46 (`epics-and-stories-epic-46.md`); it does not replace it.
> **Read this file end to end before touching code.** Every path below exists (or is named as missing) as of commit `8ac8561d`.

---

## 0. How to use this document

You are implementing a missing piece in an existing, running Java trading system. You cannot ask questions mid-task; the four decisions you might want to ask are pre-answered in §10, and everything else is a constraint, not a choice.

Read in this order before writing any code:

1. `AGENT.md` / `docs/architecture-trading-bridge-java.md` (system overview)
2. `docs/TRADING-GUARDRAILS.md` (what is forbidden on the order path)
3. `trading-runtime/src/main/java/com/martinfou/trading/runtime/BarCloseScheduler.java`
4. `trading-strategies/src/main/java/com/martinfou/trading/strategies/LiveStrategyRunner.java` (read the class, do **not** modify it)
5. `trading-broker/src/main/java/com/martinfou/trading/broker/IbkrBroker.java`
6. `trading-data/src/main/java/com/martinfou/trading/data/ibkr/TcpIbkrGatewayClient.java`
7. `scripts/docker-entrypoint.sh`
8. `docker-compose.yml`

Then implement §5 work items in order. Commit and push after each one. Do not batch them.

---

## 1. What is actually deployed and running (measured 2026-10-29 19:20 EDT)

| Component | State | Evidence |
|---|---|---|
| `trading-live` (container) | Up, healthy, 44 h | 4 strategies GBP_JPY H1 on OANDA practice account `-014`, deployed 2026-10-27 23:42 EDT |
| `trading-dev` (container) | Up, healthy, idle | dev lane, isolated |
| `ib-gateway` (container) | Up, 3 days | **separate compose project** at `~/ibkr-gateway`, IB Gateway 10.50 paper, port 4002, user `martinfoupaper`, **zero consumers** |
| Live futures runner | **does not exist** | no container, no `main()`, no class |

The IB Gateway is already paid for and already running. Nothing is connected to it.

---

## 2. The gap

Epic 46 is marked **100 % done** in `_bmad-output/sprint-status-dashboard.md`. In reality every *component* of it was written and **none of it was ever assembled into a process.** Measured with an exhaustive `grep -rln '<Class>' --include=*.java` over the main source tree:

**Exists, tested, and has ZERO caller in production code:**

| Class | Path |
|---|---|
| `BarCloseScheduler` | `trading-runtime/src/main/java/com/martinfou/trading/runtime/BarCloseScheduler.java` |
| `IbkrBracketOrderRouter` | `trading-broker/src/main/java/com/martinfou/trading/broker/IbkrBracketOrderRouter.java` |
| `IbkrOrderExecutionManager` | `trading-broker/src/main/java/com/martinfou/trading/broker/IbkrOrderExecutionManager.java` |
| `IbkrStateReconciler` | `trading-broker/src/main/java/com/martinfou/trading/broker/IbkrStateReconciler.java` |
| `CmeAutoRolloverManager` | `trading-broker/src/main/java/com/martinfou/trading/broker/CmeAutoRolloverManager.java` |
| `IbkrGatewaySupervisor` | `trading-broker/src/main/java/com/martinfou/trading/broker/IbkrGatewaySupervisor.java` |
| `IbkrHeartbeatWatchdog` | `trading-data/src/main/java/com/martinfou/trading/data/ibkr/IbkrHeartbeatWatchdog.java` |

The only references are their own files and their test classes.

**Does not exist at all:**

- `IbkrLiveRunner.java`, the class Epic 46 explicitly names. It was never created.
- Any reusable IBKR **bar source**. `IbkrHistoricalDataDownloader` is a standalone `main()` CLI (called only by `ConnectorRunner`), not a library API. `BarSourceResolver` reads `.bars` files from disk only.
- Any compose service for a futures lane.
- Any entrypoint that can start a non-OANDA runner.

**And the scheduler that would host all this does not honour its own contract:**

| Defect in `BarCloseScheduler` | Line | Consequence |
|---|---|---|
| `scheduleAtFixedRate(this::runCycleSafe, 1, 60, MINUTES)` | 74-77 | First fire is 1 minute after **process start**, not at `:00:05` after a bar close. Phase drifts with every restart. There is no `:00:05` anywhere in the class. |
| `runCycle(Instant.now(), 5000.0)` | 139 | Account equity is a hardcoded `5000.0`. |
| `marginGuard.validateOrder(o, accountEquity, List.of())` | 114-116 | Open positions are always an **empty list**, so `SmallAccountMarginGuard`'s `DEFAULT_MAX_CONCURRENT_POSITIONS = 2` can never trigger. It is dead protection. |
| `Function<List<Bar>, List<Order>> strategyEvaluator` | 30, 108 | One evaluator for **all** symbols. No strategy identity, no per-strategy state, no position awareness between cycles. Stateless per cycle by design. |
| Fixed 60-minute cadence | 76 | The two candidate strategies are **M30**. An hourly scheduler cannot run them. |

---

## 3. Scope

### In scope (MVP — this document)

One instrument: **MGC** (Micro Gold, COMEX, 10 oz), on the **IBKR paper account on port 4002**.
One or both of the two already-registered MGC strategies:

- `sq-mgc-m30-breakout-0-24542` → `trading-strategies/src/main/java/com/martinfou/trading/strategies/sqimported/SqMgcM30Breakout024542.java`
- `sq-mgc-m30-breakout-0-32921` → `trading-strategies/src/main/java/com/martinfou/trading/strategies/sqimported/SqMgcM30Breakout032921.java`

Both are already in `getStrategyMap()` (in `LiveStrategyRunner`) and in `config/live-config.json` at `0.5 %` risk each. Fixed sizing of **1 micro contract**, as the strategy code states.

The MVP succeeds when a strategy-generated signal becomes a **filled, stop-protected order on the IBKR paper account, unattended, at a bar close.**

### Explicitly out of scope

- MES, MNQ, M2K, MCL. One symbol. The scheduler must stay parameterized so they can be added later, but do not add them.
- Automatic quarterly rollover (`CmeAutoRolloverManager` exists; do **not** wire it). MVP resolves the front-month contract once at startup and logs the expiry. Manual roll.
- Native CME OCA brackets as specified in story 46-2. The MVP routes entry + protective stop through `IbkrBroker.submitOrder`. Bracket parity is a follow-up.
- Any change to the OANDA `LiveStrategyRunner`. Do not refactor it into a shared abstraction. Copy the pattern, do not share the code, in the MVP.
- Any change to the deployed GBP_JPY strategies, their risk %, or `ACCOUNT_RISK_CAP_PCT`.
- Account `-005` (live money). Never.
- Web hub / dashboard surface. The monitor JSON is enough.

---

## 4. Target architecture

```
docker compose (project tb-futures, container trading-futures)
│
└─ IbkrLiveRunner.main()                    ← NEW, the missing host
    ├─ IbkrConnectionConfig(host, 4002, clientId, account)   (existing)
    ├─ TcpIbkrGatewayClient → IbkrBroker                       (existing)
    ├─ IbkrGatewaySupervisor                                   (existing, now called)
    ├─ IbkrStateReconciler                                     (existing, now called)
    ├─ SmallAccountMarginGuard(0.50, 2)                        (existing)
    ├─ IbkrBarSource                                           ← NEW (reqHistoricalData → List<Bar>)
    ├─ StrategyEvaluator (per symbol, holds Strategy instances) ← NEW (onBar → getPendingOrders)
    ├─ IbkrOrderDispatcher (Order → bracket → submitOrder)      ← NEW
    └─ BarCloseScheduler(:00:05, intervalMinutes=30)           (existing, FIXED)
```

**Ownership rules (do not violate):**

- **The broker is the source of truth for positions.** `IbkrBroker.getPositions()` at every cycle feeds `SmallAccountMarginGuard`. Do not invent a local position ledger in the MVP.
- **The scheduler owns timing, nothing else.** No strategy logic inside it.
- **`IbkrLiveRunner` is the only composition root of the futures lane.** It must not be reachable from `ControlPlaneMain` in this MVP.
- **State:** one JSON file under `TB_STATE_DIR` (default `/app/data-futures/state`) containing the last processed bar timestamp per symbol, so a restart does not re-evaluate a bar it already traded. This is the *only* state the MVP persists.

---

## 5. Work items

Each item is committable on its own. Do not start the next before the previous builds and its tests pass.

### 46-7 — Make `BarCloseScheduler` honour its contract

**Files:** `trading-runtime/src/main/java/com/martinfou/trading/runtime/BarCloseScheduler.java` (modify), `trading-runtime/src/test/java/com/martinfou/trading/runtime/BarCloseSchedulerEnsembleTest.java` (modify), plus a new `BarCloseSchedulerTimingTest`.

Required behaviour:

1. Constructor accepts `int intervalMinutes` (30 for M30, 60 for H1) and a `Clock` (default `Clock.systemUTC()`), so timing is testable without sleeping.
2. `start()` computes the **next** boundary at `wallClock + interval` truncated down, plus **5 seconds**, and schedules from there; after each run it reschedules. Never `scheduleAtFixedRate` with a raw initial delay.
3. `accountEquity` comes from a `DoubleSupplier` injected at construction, backed by `IbkrBroker.getAccountState().netLiquidation()`. Delete the `5000.0`.
4. `runCycle` receives real open positions from a `Supplier<List<Position>>` and passes them to `marginGuard.validateOrder(order, equity, openPositions)`. Delete the `List.of()`.
5. The evaluator becomes `Function<String, List<Order>>` (symbol → orders) so each symbol has its own strategy instance, or a `Map<String, Function<List<Bar>, List<Order>>>`. Choose one and document it in the javadoc.
6. Skip, and log why, when the last closed bar is older than `interval + 2 minutes` (stale data must not be traded).

**Acceptance criteria**

- A unit test with a fixed `Clock` proves the first trigger is at `HH:MM:05` and that consecutive triggers are exactly `intervalMinutes` apart.
- A unit test proves `validateOrder` receives a non-empty position list when the supplier returns positions.
- A unit test proves a stale bar list produces zero orders and a WARN log.
- A unit test proves the equity supplier is called (not a constant).
- Existing `BarCloseSchedulerEnsembleTest` still passes, updated to the new constructor.

### 46-8 — `IbkrBarSource`: bar fetch usable from Java

**File:** `trading-data/src/main/java/com/martinfou/trading/data/ibkr/IbkrBarSource.java` (new)

`IbkrHistoricalDataDownloader` already speaks `reqHistoricalData` through the TWS socket, but it is a `main()`. Extract the request/response into a reusable class:

```java
public final class IbkrBarSource {
    IbkrBarSource(IbkrConnectionConfig config);
    List<Bar> latestBars(String symbol, String timeframe, int count); // e.g. ("MGC","30 mins",60)
    void close();
}
```

Rules:

- Returns `Bar` records from `trading-core` (`symbol`, `timestamp` as UTC `Instant`, ohlcv). IBKR delivers bar times in the exchange timezone: convert to `Instant` at the boundary and nowhere else.
- Subscribe **inclusive of the last closed bar**, exclude the still-forming one, and drop any bar whose `timestamp + timeframe > now`.
- One dedicated `clientId`, distinct from the runner's (IBKR refuses two connections on one clientId, error 326).
- Do not reuse `IbkrHistoricalDataDownloader`'s `main()`; leave it alone if it is still used by `ConnectorRunner`.

**Acceptance criteria**

- An integration test **guarded to skip when no gateway is reachable** (`Assumptions.assumeTrue` on a socket probe) returns ≥ 10 MGC M30 bars, monotonically increasing, all in the past.
- A unit test proves the still-forming bar is dropped, using a fake client.

### 46-9 — `IbkrLiveRunner`: assemble and route

**File:** `trading-runtime/src/main/java/com/martinfou/trading/runtime/ibkr/IbkrLiveRunner.java` (new), plus
`.../ibkr/SqStrategyEvaluator.java` and `.../ibkr/IbkrOrderDispatcher.java` (new).

**`main(String[] args)` contract** (document it in the class javadoc and in the compose file):

| Env | Required | Meaning |
|---|---|---|
| `IBKR_HOST` | no, default `127.0.0.1` | gateway host |
| `IBKR_PORT` | no, default `4002` | **refuse to start on 4001, 7496, 7497** (live ports) |
| `IBKR_ACCOUNT_ID` | yes | paper account id |
| `IBKR_CLIENT_ID` | no, default `11` | must differ from `IbkrBarSource`'s |
| `FUTURES_SYMBOL` | no, default `MGC` | one symbol, refuse a comma-separated list |
| `FUTURES_TIMEFRAME` | no, default `30 mins` | must pair with `FUTURES_INTERVAL_MINUTES` |
| `FUTURES_INTERVAL_MINUTES` | no, default `30` | scheduler cadence |
| `STRATEGY` | yes | space-separated strategy names, resolved from `getStrategyMap()` |
| `TB_ALLOW_ORDERS` | yes, must be `1` | `OrderTripwire` refuses otherwise |
| `TB_STATE_DIR` | no, default `/app/data-futures/state` | bar cursor persistence |
| `DRY_RUN` | no, default `false` | `true` = evaluate and log, dispatch nothing |

Startup sequence, in this exact order (order matters, see §7):

1. Refuse live ports, refuse a missing account, refuse a non-MGC timeframe/interval mismatch.
2. Resolve the contract: `IbkrContractResolver.resolve(symbol, LocalDate.now(EASTERN_ZONE))`. Log the resolved expiry and the days to expiry. Refuse to start if fewer than 3 days remain.
3. Connect `IbkrBroker`. Log `getAccountState()`.
4. `IbkrGatewaySupervisor` up. Connect `IbkrStateReconciler`; run one reconciliation and **log any position the broker holds that the runner does not expect.**
5. Instantiate every strategy from `STRATEGY` via the same `getStrategyMap()` reflection `LiveStrategyRunner` uses. Fail closed if a name is unknown.
6. Load the bar cursor from `TB_STATE_DIR`.
7. Build and `start()` the `BarCloseScheduler`.
8. Register a shutdown hook that stops the scheduler, closes the socket, and persists the cursor.

**`IbkrOrderDispatcher` rules:**

- Refuse any entry without a protective stop (`Order.hasProtectiveStop()`). An entry with `stopLoss <= 0` is rejected locally and logged at ERROR. This is the same rule the live OANDA path enforces.
- Apply `IbkrBracketOrderRouter.createBracket(entry, sl, tp)` when both are present; otherwise submit the entry and attach the stop immediately.
- Tag the order `strategyId` = `<strategy>_<symbol>` so attribution works the same way as the OANDA lane.
- Log one line per dispatch: strategy, side, quantity, price, stop, take-profit, and the broker order id.
- Idempotence: never dispatch two orders for the same `(barTimestamp, strategy, symbol)`.

**Acceptance criteria**

- `--dry-run` against the live gateway at a real bar close produces a log line with the bar count, the evaluation result, and a "would dispatch" line or an explicit "no signal".
- A unit test with a fake broker proves a rejected entry (no stop) never reaches `submitOrder`.
- A unit test proves the same bar is not dispatched twice after a restart (cursor).
- A unit test proves the runner refuses `IBKR_PORT=7496`.
- `docker logs` at startup contains, in order: port check, contract + expiry, account state, reconciliation result, strategy list, scheduler start with the **next trigger timestamp**.

### 46-10 — Deployment surface

**Files:** `scripts/docker-entrypoint-ibkr.sh` (new), `docker/docker-compose.futures.yml` (new), `Dockerfile` (modify — one ENV line, nothing else), `.gitignore` (add `data-futures/`).

Rules:

- **Do not touch `scripts/docker-entrypoint.sh`.** It hardcodes `com.martinfou.trading.strategies.LiveStrategyRunner` and exits when `OANDA_API_KEY` is absent. Write a second entrypoint that launches `IbkrLiveRunner` and validates `IBKR_ACCOUNT_ID` instead.
- **Do not add a service to the main `docker-compose.yml`.** An unqualified `docker compose up -d` would start it, and this repository has already been burned by a service declared but never created (`nfp-week`). Use a separate compose file with its own project name:
  `docker compose -f docker/docker-compose.futures.yml --project-name tb-futures up -d`
- **`container_name` is hardcoded in the main compose.** Your new service must use a unique name (`trading-futures`) and the project name must differ from the default, or it will collide with the live lane.
- **Do not mount `./data`.** It holds the OANDA lane's runner state; two lanes writing it corrupt each other. Mount `./data-futures:/app/data-futures` and set `TB_STATE_DIR=/app/data-futures/state`.
- The image is the same `Dockerfile`. Set `TB_ALLOW_ORDERS=1` and `IBKR_*` in the futures compose only.
- **Healthcheck:** the existing one greps for the `LiveStrategyRunner` process. Write a futures-specific healthcheck that greps for `IbkrLive` + `Runner` (split the literal, for the same reason the existing one is split: a probe that greps its own command line always reports healthy).
- Network: the container must reach the gateway. The gateway publishes `4002` on the host; use `host.docker.internal` (add `extra_hosts: ["host.docker.internal:host-gateway"]`) rather than joining the gateway's project.

**Acceptance criteria**

- `docker compose -f docker/docker-compose.futures.yml --project-name tb-futures config` renders.
- The container builds from the same image (`docker inspect` shows the same `Image` id as `trading-live`) and starts healthy.
- `docker ps` shows **four** containers and `trading-live`'s uptime is unchanged (prove you did not recreate it).
- Stopping and restarting `trading-futures` preserves the bar cursor file.
- `git status` shows `data-futures/` ignored.

### 46-11 — Correct the status record

Epic 46 is marked 100 % while 6 components have no caller and `IbkrLiveRunner` was never created. That is what caused the current request. Fix the record, in the same PR:

- `_bmad-output/sprint-status-dashboard.md`: Epic 46 back to in-progress, with 46-7..46-10 as the open stories.
- `_bmad-output/implementation-artifacts/46-6-....md`: its checkboxes are `[ ]` while the story is `done`. Either check what is genuinely done or mark the story `review`.
- `docs/paper-window-log.md`: a dated entry recording that the futures lane was found undeployed, with this document as the reference.

**Acceptance criteria:** a reader landing on the sprint dashboard can no longer believe the futures lane is live.

---

## 6. Hard constraints (violating one of these is a failed task, not a bug)

1. **Never send an order to a live IBKR port.** Allowed: `4002` (paper). Refused: `4001`, `7496`, `7497`. Assert it, do not document it.
2. **Never touch OANDA account `-014`.** The futures lane talks to IBKR only. Prove it: `lastTransactionID` of `-014` is unchanged before and after your full test run.
3. **Never `docker compose up -d` in the repo root.** It recreates `trading-live` and destroys `/tmp` runner state of the deployed GBP_JPY strategies.
4. **Never modify** `LiveStrategyRunner.java`, `strategies/sqimported/**`, `config/live-config.json` risk values, or `ACCOUNT_RISK_CAP_PCT`.
5. **Every entry needs a protective stop.** No exception, not even in dry-run.
6. **Java 21.** GitHub CI compiles with `--release 21` even though the local toolchain may be newer. Do not use Java 22+ language features.
7. **Spotbugs is wired into the real build.** No dead private methods left behind.
8. **Commits:** `git -c commit.gpgsign=false commit`. Never `git add -A` (the running container writes into `data/runtime/`). Stage by path.
9. **Push every commit immediately**, on the branch and to the default branch. A local-only commit is not delivered work.
10. **Do not run a second Maven build while a deploy gate is running.**

---

## 7. Traps specific to this repository (each one already cost time to someone)

1. **The jar does not exist.** `docker/docker-compose.ibkr.yml` runs `java -jar trading-runtime.jar`. The `Dockerfile` ships unpacked classes in `/app/classes/<module>` plus third-party jars in `/app/libs`. That service could never start. Do not "fix" it by adding a jar; use the classpath that the working entrypoint uses.
2. **Classes, not jars, prove deployment.** To prove which build runs: `docker exec trading-futures stat -c %y /app/classes/trading-runtime/com/martinfou/trading/runtime/ibkr/IbkrLiveRunner.class`, and grep the deployed classes for a string literal **your** change introduced.
3. **`Bar` timestamps are UTC `Instant`; IBKR bar times are exchange (America/New_York) time.** Convert once, at the socket boundary. `CmeGlobexMarketCalendar.EASTERN_ZONE` is the reference zone in this codebase.
4. **The still-forming bar.** At `:00:05` the bar that just closed is complete but `reqHistoricalData` may also return a partial one. Drop it explicitly or the strategy evaluates an incomplete candle.
5. **Two connections, one clientId.** IBKR rejects a second connection on the same clientId with error 326. `IbkrBarSource` and the runner need distinct ids.
6. **`OrderTripwire` is fail-closed.** Without `TB_ALLOW_ORDERS=1` every order is refused, which looks exactly like a broken strategy.
7. **M30 vs hourly.** The two MGC strategies are M30. An hourly scheduler silently halves their trade frequency; there is no error, just missing trades.
8. **`docker logs --since` parses the argument in LOCAL time** while the printed timestamps are UTC. Converting the wrong way returns the wrong hour.
9. **The deploy script's state restore only covers `/tmp`-era layouts.** A new lane uses the mounted state dir; do not copy files into `/tmp` of a container you are about to recreate.
10. **A strategy latch can survive a restart.** If you find a strategy "in trade" with no open position, that is a known defect class in this repo; do not paper over it in the futures lane, log it and stop.

---

## 8. Verification commands (copy-paste)

```bash
cd ~/projects/trading-bridge

# build + tests (Java 21 via mise)
export JAVA_HOME=$HOME/.local/share/mise/installs/java/26.0.1
export PATH="$JAVA_HOME/bin:$HOME/.local/share/mise/installs/maven/3.9.16/apache-maven-3.9.16/bin:$PATH"
./mvnw -B -DskipITs -Dspotbugs.skip=true test

# one module only, and one class only (note the surefire- prefix)
./mvnw -B -DskipITs -Dspotbugs.skip=true -pl trading-runtime -am \
  -Dtest=BarCloseSchedulerTimingTest -Dsurefire.failIfNoSpecifiedTests=false test

# gateway reachable?
nc -z 127.0.0.1 4002 && echo "gateway up"

# futures lane
docker compose -f docker/docker-compose.futures.yml --project-name tb-futures up -d
docker logs -f trading-futures

# proof the OANDA lane was not touched
python3 - <<'PY'
import json,urllib.request
env={}
for line in open('.env.paper'):
    line=line.strip()
    if line and not line.startswith('#') and '=' in line:
        k,v=line.split('=',1); env[k.strip()]=v.split('#')[0].strip().strip('"').strip("'")
req=urllib.request.Request(f"https://api-fxpractice.oanda.com/v3/accounts/{env['OANDA_ACCOUNT_ID']}/summary",
    headers={"Authorization":"Bearer "+env['OANDA_API_KEY']})
a=json.load(urllib.request.urlopen(req))["account"]
print("OANDA lastTransactionID", a["lastTransactionID"], "openTrades", a["openTradeCount"])
PY
```

---

## 9. Definition of done

All of the following must be **observed**, not asserted:

1. `trading-futures` container running, healthy, with `trading-live` uptime unchanged.
2. Startup log shows: port check OK, MGC contract + expiry, account state, reconciliation result, strategy list, and a **next trigger timestamp ending in `:00:05`**.
3. At least one real M30 bar close processed unattended, with a log line naming the bar timestamp and the evaluation outcome.
4. Either a real signal was dispatched, or a forced single-bar replay in a test produced one — with: fill confirmed by `IbkrBroker.getPositions()`, a protective stop present, and the order tagged `<strategy>_MGC`.
5. Restarting the container does not re-trade the same bar.
6. `TB_ALLOW_ORDERS=1` confirmed present in the container env; flipping it to `0` produces a refusal (prove the guard is alive).
7. OANDA `-014` `lastTransactionID` identical before and after the whole exercise.
8. `./mvnw test` green; CI green on the branch and on the default branch.
9. Epic 46 status corrected (see 46-11).

---

## 10. Open decisions (pre-answered defaults — Martin may override)

| # | Decision | Default if unopposed | Why |
|---|---|---|---|
| D1 | One MGC strategy or both? | **Both**, each with its own state, 1 contract each, sharing the margin guard | They are independent backtests; the guard is what keeps total risk bounded |
| D2 | Separate compose project (`tb-futures`) or a `profiles:` entry in the main compose? | **Separate project** | An unqualified `up -d` must never start a second trading lane |
| D3 | Fix the scheduler in place or write a futures-specific scheduler? | **Fix in place** | The defects (hardcoded equity, empty positions, no `:00:05`) are bugs wherever it runs |
| D4 | Wire `CmeAutoRolloverManager` now? | **No.** Resolve front month at startup, log expiry, alert at T-3 days, roll by hand | Auto-rollover is a separate risk surface; the MVP proves the signal-to-fill path first |

If Martin overrides one, record it in `_bmad-output/planning-artifacts/prds/prd-ibkr-futures-2026-06-15/.decision-log.md` as the next free `D<n>` (that file is the canonical decision log for this feature, series continues there).

---

## 11. Non-goals, restated so nobody "improves" them

- No refactor of the OANDA runner.
- No shared abstraction between the two lanes in the MVP.
- No new dashboard page, no hub API change.
- No automated strategy promotion, no ensemble weighting, no regime switching.
- No live account, at any point, for any reason.
