# Tech Spec — IBKR Live Futures Lane (the missing runner)

> **Status**: DRAFT v2 — Party Mode round integrated (Winston / Amelia / John); 5 decisions default in §10
> **Revision 2**: adds the finding that changes the MVP — **the IBKR execution layer can only place MARKET orders, so no protective stop can reach the venue today.** See §2.3; story 46-8 is new and blocking.
> **Audience**: a powerful external coding agent (Claude Code, Codex, agy, or equivalent) with **no session memory**
> **Author**: Hermes (2026-10-29), from a live audit of the running stack
> **Supersedes nothing.** This document **extends** Epic 46 (`epics-and-stories-epic-46.md`); it does not replace it.
> **Read this file end to end before touching code.** Every path below exists (or is named as missing) as of commit `b8614e14`.

---

## 0. How to use this document, and the three ways you will get this wrong

You are implementing a missing piece in an existing, running Java trading system. You cannot ask questions mid-task: the decisions you might want to ask are pre-answered in §10, and everything else is a constraint, not a choice.

**Three failure modes, measured on this repository, in order of likelihood:**

1. **You touch the live FX lane.** Four real GBP_JPY strategies are trading on OANDA account `-014` right now. `LiveStrategyRunner`, `scripts/docker-entrypoint.sh`, the risk values in `config/live-config.json` and the `trader` service in `docker-compose.yml` all belong to that lane. Read them, never modify them. Never run `docker compose up -d` at the repo root: it recreates that container and its `/tmp` state is lost.
2. **You trust the sprint dashboard.** It says Epic 46 is 100 % complete. Every component it delivered has **zero production caller**, the runner it names was never written, and the execution layer cannot even send a stop. §2 has the measurements.
3. **You over-specify.** Native OCA brackets, quarterly rollover, the 50 % margin shield and the multi-position limiter are all specified in Epic 46 and all **out of scope here**. §11 lists them. Building them first is how this delivery fails.

Read in this order before writing any code:

1. `docs/architecture-trading-bridge-java.md` (system overview). This repo has **no** `AGENT.md` and no `AGENTS.md`: do not go looking for one, and do not create one.
2. `docs/TRADING-GUARDRAILS.md` (what is forbidden on the order path)
3. `trading-runtime/src/main/java/com/martinfou/trading/runtime/BarCloseScheduler.java`
4. `trading-strategies/src/main/java/com/martinfou/trading/strategies/LiveStrategyRunner.java` (read the class, do **not** modify it)
5. `trading-broker/src/main/java/com/martinfou/trading/broker/IbkrBroker.java`
6. `trading-data/src/main/java/com/martinfou/trading/data/ibkr/TcpIbkrGatewayClient.java`
7. `scripts/docker-entrypoint.sh`
8. `docker-compose.yml`

Then implement §5 in order. Commit and push after each item. Do not batch them.

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

Epic 46 is marked **100 % done** in `_bmad-output/sprint-status-dashboard.md`. In reality every *component* of it was written and **none of it was ever assembled into a process.** Measured with an exhaustive `grep -rln '<Class>' --include=*.java` over the main source tree.

### 2.1 Exists, tested, and has ZERO caller in production code

The only references are each file itself and its test classes.

| Class | Path |
|---|---|
| `BarCloseScheduler` | `trading-runtime/src/main/java/com/martinfou/trading/runtime/BarCloseScheduler.java` |
| `IbkrBracketOrderRouter` | `trading-broker/src/main/java/com/martinfou/trading/broker/IbkrBracketOrderRouter.java` |
| `IbkrOrderExecutionManager` | `trading-broker/src/main/java/com/martinfou/trading/broker/IbkrOrderExecutionManager.java` |
| `IbkrStateReconciler` | `trading-broker/src/main/java/com/martinfou/trading/broker/IbkrStateReconciler.java` |
| `CmeAutoRolloverManager` | `trading-broker/src/main/java/com/martinfou/trading/broker/CmeAutoRolloverManager.java` |
| `IbkrGatewaySupervisor` | `trading-broker/src/main/java/com/martinfou/trading/broker/IbkrGatewaySupervisor.java` |
| `IbkrHeartbeatWatchdog` | `trading-data/src/main/java/com/martinfou/trading/data/ibkr/IbkrHeartbeatWatchdog.java` |

### 2.2 Does not exist at all

- `IbkrLiveRunner.java`, the class Epic 46 explicitly names. It was never created.
- Any reusable IBKR **bar source**. `IbkrHistoricalDataDownloader` is a standalone `main()` CLI (called only by `ConnectorRunner`), not a library API. `BarSourceResolver` reads `.bars` files from disk only.
- Any compose service for a futures lane.
- Any entrypoint that can start a non-OANDA runner.

### 2.3 The blocking finding: no protective stop can reach the venue

This is why the components above are orphaned, and it is the real last mile.

```java
// trading-broker/.../IbkrBroker.java:134
if (order.type() != Order.Type.MARKET) {
    emit(BrokerEvent.reject(order, "IbkrBroker supports MARKET orders only"));
    return OrderSubmitResult.rejected("IbkrBroker supports MARKET orders only");
}
```

- `IbkrBroker.submitOrder` accepts `MARKET` **only**, and **silently ignores `order.stopLoss()` and `order.takeProfit()`**. No log, no warning, no reject: the fields are simply dropped.
- `IbkrGatewayClient` (the session contract) exposes exactly one placement method: `placeMarketOrder(...)`. There is no stop-order or limit-order method.
- `TcpIbkrGatewayClient` builds orders through a single helper, `buildMarketOrder(...)` (lines 393/402). No other order type is ever constructed.
- `IbkrBracketOrderRouter.createBracket(entry, sl, tp)` **builds** `Order` objects of type `STOP` and `LIMIT` with an OCA group. Nothing in the codebase can send one: such an `Order` is rejected at line 134 the moment it reaches `IbkrBroker`.

Consequence: **`Order.hasProtectiveStop()` exists, the OANDA path enforces it, and on IBKR it is unenforceable.** Any entry placed through today's IBKR stack is a naked position. This single fact decides the shape of the MVP (§10 D5).

### 2.4 The scheduler does not honour its own contract

| Defect in `BarCloseScheduler` | Line | Consequence |
|---|---|---|
| `scheduleAtFixedRate(this::runCycleSafe, 1, 60, MINUTES)` | 74-77 | First fire is 1 minute after **process start**, not at `:00:05` after a bar close. Phase drifts with every restart. There is no `:00:05` anywhere in the class. |
| `runCycle(Instant.now(), 5000.0)` | 139 | Account equity is a hardcoded `5000.0`. |
| `marginGuard.validateOrder(o, accountEquity, List.of())` | 114-116 | Open positions are always an **empty list**, so `SmallAccountMarginGuard`'s `DEFAULT_MAX_CONCURRENT_POSITIONS = 2` can never trigger. It is dead protection. |
| `Function<List<Bar>, List<Order>> strategyEvaluator` | 30, 108 | One evaluator for **all** symbols. No strategy identity, no per-strategy state, no position awareness between cycles. Stateless per cycle by design. |
| Fixed 60-minute cadence | 76 | The candidate strategies are **M30**. An hourly scheduler cannot run them. |

---

## 3. Scope

### In scope (MVP — this document)

One instrument: **MGC** (Micro Gold, COMEX, 10 oz), on the **IBKR paper account, port 4002**.

**One strategy for the first verified loop:**

- `sq-mgc-m30-breakout-0-24542` → `trading-strategies/src/main/java/com/martinfou/trading/strategies/sqimported/SqMgcM30Breakout024542.java` (ELITE, score 96.0)

It is already registered in `getStrategyMap()` (in `LiveStrategyRunner`) and already has an entry in `config/live-config.json` at `0.5 %` risk. Fixed sizing of **1 micro contract**, as the strategy code states.

The second MGC strategy, `sq-mgc-m30-breakout-0-32921` (`SqMgcM30Breakout032921.java`, ELITE 92.6), is added **only after** the first loop has produced one verified fill with a stop at the venue. It is a config name, not design work: same code path.

The MVP succeeds when a strategy-generated signal becomes a **filled, stop-protected order on the IBKR paper account, unattended, at a bar close.**

### Explicitly out of scope

- MES, MNQ, M2K, MCL. One symbol. The scheduler must stay parameterized so they can be added later, but do not add them.
- Automatic quarterly rollover (`CmeAutoRolloverManager` exists; do **not** wire it). The MVP resolves the front-month contract once at startup and logs the expiry. Manual roll.
- Full CME OCA bracket parity as specified in story 46-2. The MVP sends the entry and its protective stop as two venue orders built by `IbkrBracketOrderRouter`; validating OCA cancellation semantics is a follow-up.
- Any change to the OANDA `LiveStrategyRunner`. Do not refactor it into a shared abstraction. Copy the pattern, do not share the code, in the MVP.
- Any change to the deployed GBP_JPY strategies, their risk %, or `ACCOUNT_RISK_CAP_PCT`.
- Account `-005` (live money). Never.
- Web hub / dashboard surface. The monitor JSON is enough.

---

## 4. Target architecture

```
docker compose (project tb-futures, container trading-futures)
│
└─ IbkrLiveRunner.main()                          ← NEW: the composition root
    ├─ IbkrConnectionConfig(host, 4002, clientId, account)   (existing)
    ├─ TcpIbkrGatewayClient → IbkrBroker                       (existing, EXTENDED for stops — 46-8)
    ├─ IbkrGatewaySupervisor                                   (existing, finally called)
    ├─ IbkrStateReconciler                                     (existing, finally called)
    ├─ SmallAccountMarginGuard(0.50, 2)                        (existing)
    ├─ IbkrBarSource                                           ← NEW (reqHistoricalData → List<Bar>)
    ├─ SqStrategyEvaluator (one Strategy instance per name)     ← NEW (onBar → getPendingOrders)
    ├─ IbkrOrderDispatcher (entry + venue stop + tag)           ← NEW
    └─ BarCloseScheduler(:00:05, intervalMinutes=30)           (existing, FIXED)
```

**Ownership rules (do not violate):**

- **The broker is the source of truth for positions.** `IbkrBroker.getPositions()` at every cycle feeds `SmallAccountMarginGuard`. Do not invent a local position ledger in the MVP.
- **The scheduler owns timing, nothing else.** No strategy logic inside it.
- **`IbkrLiveRunner` is the only composition root of the futures lane.** It must not be reachable from `ControlPlaneMain` in this MVP.
- **State:** one JSON file under `TB_STATE_DIR` (default `/app/data-futures/state`) holding the last processed bar timestamp per symbol, so a restart does not re-evaluate a bar it already traded. This is the *only* state the MVP persists.
- **A stop that is not at the venue does not count.** If the protective stop order was not accepted, the position is naked: log ERROR, flatten, halt the lane. Never "keep going and watch it".

---

## 5. Work items

Each item is committable on its own. Do not start the next before the previous builds and its tests pass.

### Stage 0 — Prove the existing fill path (no code)

Before writing anything, prove the execution path works from this machine: `PaperMgcSmokeTest` (`trading-examples/src/main/java/com/martinfou/trading/examples/PaperMgcSmokeTest.java`) already places 1 MGC market order on the paper account, verifies the position, then flattens. Run it with `TB_ALLOW_ORDERS=1` and the gateway on `4002`.

**Done when:** a MGC fill is observed and the position is flat again. If this fails, stop: the gateway or the account is the problem and nothing below can succeed.

### 46-7 — Make `BarCloseScheduler` honour its contract

**Files:** `trading-runtime/src/main/java/com/martinfou/trading/runtime/BarCloseScheduler.java` (modify), `trading-runtime/src/test/java/com/martinfou/trading/runtime/BarCloseSchedulerEnsembleTest.java` (update), new `BarCloseSchedulerTimingTest.java`.

1. Constructor takes `int intervalMinutes` (30 for M30, 60 for H1) and a `Clock` (default `Clock.systemUTC()`), so timing is testable without sleeping.
2. `start()` schedules to the **next** boundary: truncate now to the interval, add the interval, add 5 seconds. Reschedule after each run. Never `scheduleAtFixedRate` with a raw initial delay.
3. `accountEquity` comes from an injected `DoubleSupplier` backed by `IbkrBroker.getAccountState()`. Delete the `5000.0`.
4. Positions come from an injected `Supplier<List<Position>>` and are passed to `marginGuard.validateOrder(order, equity, openPositions)`. Delete the `List.of()`.
5. The evaluator becomes symbol-keyed (`Map<String, Function<List<Bar>, List<Order>>>` or equivalent) so each symbol has its own `Strategy` instance and state. Document the choice in the javadoc.
6. Skip, and log why, when the newest closed bar is older than `interval + 2 minutes` (never trade stale data).

**Acceptance criteria**

- With a fixed `Clock`, the first trigger lands at `HH:MM:05` and consecutive triggers are exactly `intervalMinutes` apart.
- `validateOrder` receives a non-empty position list when the supplier returns positions.
- A stale bar list produces zero orders plus a WARN.
- The equity supplier is invoked (prove it is not a constant).
- The existing `BarCloseSchedulerEnsembleTest` passes against the new constructor.

### 46-8 — IBKR stop-order support (BLOCKING)

Without this, no entry can be protected and the DoD in §9 is unreachable.

**Files:** `trading-data/src/main/java/com/martinfou/trading/data/ibkr/IbkrGatewayClient.java`, `TcpIbkrGatewayClient.java`, `StubIbkrGatewayClient.java`, `trading-broker/src/main/java/com/martinfou/trading/broker/IbkrBroker.java` (all modify), plus tests.

1. Add a placement method for protective orders to `IbkrGatewayClient`, for example
   `IbkrMarketOrderResult placeStopOrder(String symbol, double quantity, Order.Side side, double stopPrice, String clientTag, String orderRef)`
   plus the LIMIT equivalent, or one `placeOrder(String symbol, Order order, String clientTag)` that switches on `Order.Type`. Either is fine; it must be guarded by `OrderTripwire.checkOrderAllowed(...)` exactly as `placeMarketOrder` is (line ~165).
2. Implement it in `TcpIbkrGatewayClient` by adding a `buildStopOrder(...)` sibling to `buildMarketOrder(...)`: set `orderType` (`STP` / `LMT`), `auxPrice` (stop) or `lmtPrice` (limit), `tif = GTC`, `transmit = true`, `account`, `orderRef`. Reuse the existing `trackOrder` / execution-listener plumbing so stop fills still surface.
3. `IbkrBroker.submitOrder`: route on `order.type()` instead of rejecting everything but `MARKET`. For `STOP`, require a positive stop price and call `placeStopOrder`; for `LIMIT`, call the limit path. Keep the "not connected" and "no strategyId" behaviour unchanged.
4. `StubIbkrGatewayClient` must implement the new methods so existing tests keep compiling.
5. Do not remove the `MARKET`-only rejection without adding the routing: the rejection is the only reason a silent no-stop entry is currently impossible.

**Acceptance criteria**

- A unit test proves `IbkrBroker.submitOrder` no longer rejects `STOP`, and still rejects a `STOP` with a non-positive price.
- A unit test proves the stop request carries `tif=GTC` and `closeOnly`.
- A live-paper integration test (skipped when no gateway: `Assumptions.assumeTrue` on a socket probe) places one 1-contract MGC stop **far from market** so it cannot fill, reads it back, then cancels it.
- With `TB_ALLOW_ORDERS` unset, the new method is refused: assert the refusal (prove the tripwire covers the new path).

### 46-9 — `IbkrBarSource`: bar fetch usable from Java

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
- Do **not** route through `BarSourceResolver`: it reads historical `.bars` files from disk and will happily return last year's data as if it were the last hour.
- Do not reuse `IbkrHistoricalDataDownloader`'s `main()`; leave it alone if it is still used by `ConnectorRunner`.

**Acceptance criteria**

- An integration test **guarded to skip when no gateway is reachable** returns ≥ 10 MGC M30 bars, monotonically increasing, all in the past.
- A unit test with a fake client proves the still-forming bar is dropped.

### 46-10 — `IbkrLiveRunner`: assemble and route

**Files:** `trading-runtime/src/main/java/com/martinfou/trading/runtime/ibkr/IbkrLiveRunner.java` (new), plus `trading-runtime/src/main/java/com/martinfou/trading/runtime/ibkr/SqStrategyEvaluator.java` and `trading-runtime/src/main/java/com/martinfou/trading/runtime/ibkr/IbkrOrderDispatcher.java` (new). All three are in the same package.

**`main` env contract** (document it in the class javadoc and in the compose file):

| Env | Required | Meaning |
|---|---|---|
| `IBKR_HOST` | no, default `127.0.0.1` | gateway host |
| `IBKR_PORT` | no, default `4002` | **refuse to start on 4001, 7496, 7497** (live ports) |
| `IBKR_ACCOUNT_ID` | yes | paper account id |
| `IBKR_CLIENT_ID` | no, default `11` | must differ from `IbkrBarSource`'s |
| `FUTURES_SYMBOL` | no, default `MGC` | one symbol, refuse a comma-separated list |
| `FUTURES_TIMEFRAME` | no, default `30 mins` | must pair with `FUTURES_INTERVAL_MINUTES` |
| `FUTURES_INTERVAL_MINUTES` | no, default `30` | scheduler cadence |
| `STRATEGY` | yes | **one** strategy name for the MVP, resolved from `getStrategyMap()` |
| `TB_ALLOW_ORDERS` | yes, must be `1` | `OrderTripwire` refuses otherwise |
| `TB_STATE_DIR` | no, default `/app/data-futures/state` | bar cursor persistence |
| `DRY_RUN` | no, default `false` | `true` = evaluate and log, dispatch nothing |

Startup sequence, in this exact order (order matters, see §7):

1. Refuse live ports, a missing account, and a timeframe/interval mismatch.
2. Resolve the contract: `IbkrContractResolver.resolve(symbol, LocalDate.now(EASTERN_ZONE))`. Log the expiry and the days remaining. Refuse to start under 3 days.
3. Connect `IbkrBroker`. Log `getAccountState()`.
4. `IbkrGatewaySupervisor` up. Run one `IbkrStateReconciler` pass and **log any broker position the runner does not expect.**
5. Instantiate strategies from `STRATEGY` via the same `getStrategyMap()` reflection `LiveStrategyRunner` uses. Fail closed on an unknown name.
6. Load the bar cursor from `TB_STATE_DIR`.
7. Build and `start()` the `BarCloseScheduler`.
8. Register a shutdown hook that stops the scheduler, closes the socket, and persists the cursor.

**`IbkrOrderDispatcher` rules:**

- Refuse an entry with `stopLoss <= 0` locally, at ERROR. Same rule as the live OANDA path.
- Send the entry, then immediately send the protective stop built by `IbkrBracketOrderRouter.createBracket(...)`. Use the router; do not hand-roll an OCA group.
- **If the stop is rejected: log ERROR, flatten the position, halt the lane.** No naked position survives a cycle.
- Tag with `strategyId = <strategy>_<symbol>` so attribution matches the OANDA lane.
- Log one line per dispatch: strategy, side, quantity, price, stop, take-profit, broker order id.
- Idempotence: never dispatch twice for the same `(barTimestamp, strategy, symbol)`.

**Acceptance criteria**

- `DRY_RUN=true` at a real bar close logs the bar count, the evaluation result, and either a "would dispatch" line or an explicit "no signal".
- A fake-broker unit test proves a stopless entry never reaches `submitOrder`.
- A unit test proves the same bar is not dispatched twice after a restart (cursor).
- A unit test proves `IBKR_PORT=7496` is refused.
- Startup logs contain, in order: port check, contract + expiry, account state, reconciliation, strategy list, and the next trigger timestamp.

### 46-11 — Deployment surface

**Files:** `scripts/docker-entrypoint-ibkr.sh` (new), `docker/docker-compose.futures.yml` (new), `.gitignore` (add `data-futures/`).

- **Do not touch `scripts/docker-entrypoint.sh`.** It hardcodes `com.martinfou.trading.strategies.LiveStrategyRunner` and exits when `OANDA_API_KEY` is absent. Write a second entrypoint that launches `IbkrLiveRunner` and validates `IBKR_ACCOUNT_ID` and `TB_ALLOW_ORDERS` instead.
- **Do not add a service to the main `docker-compose.yml`.** An unqualified `docker compose up -d` would start a second trading lane, and this repo has already shipped a service declared but never created (`nfp-week`). Use a separate file and project name:
  `docker compose -f docker/docker-compose.futures.yml --project-name tb-futures up -d`
- **`container_name` is hardcoded in the main compose.** Name yours `trading-futures`, unique.
- **Do not mount `./data`.** It holds the OANDA lane's runner state; two lanes writing it corrupt each other. Mount `./data-futures:/app/data-futures` and set `TB_STATE_DIR=/app/data-futures/state`.
- Same image, same `Dockerfile`. Set `TB_ALLOW_ORDERS=1` and the `IBKR_*` vars in the futures compose only.
- **Healthcheck:** the existing one greps for the `LiveStrategyRunner` process. Write a futures-specific healthcheck that greps for `IbkrLive` + `Runner`, with the literal split, because a probe that greps its own command line always reports healthy.
- Networking: the gateway publishes `4002` on the host. Use `extra_hosts: ["host.docker.internal:host-gateway"]` with `IBKR_HOST=host.docker.internal` rather than joining the gateway's compose project.

**Acceptance criteria**

- `docker compose -f docker/docker-compose.futures.yml --project-name tb-futures config` renders.
- The container builds from the same image (same `Image` id as `trading-live`) and starts healthy.
- `docker ps` shows exactly **one more** container, and `trading-live`'s uptime is unchanged (prove you did not recreate it).
- Stopping and restarting `trading-futures` preserves the bar cursor file.
- `git status` shows `data-futures/` ignored.

### 46-12 — Correct the status record

Epic 46 is marked 100 % while seven components have no caller and `IbkrLiveRunner` was never created. That is what caused this request. Fix the record in the same PR:

- `_bmad-output/sprint-status-dashboard.md`: Epic 46 back to in-progress, with 46-7..46-11 as the open stories.
- `_bmad-output/implementation-artifacts/46-6-deterministic-h1-d1-bar-close-scheduler-multi-regime-ensemble-runner.md`: its checkboxes are `[ ]` while the story reads `done`. Either check what is genuinely done or move the story to `review`.
- `docs/paper-window-log.md`: a dated entry recording that the futures lane was found undeployed, with this document as the reference.

**Acceptance criteria:** a reader landing on the sprint dashboard can no longer believe the futures lane is live.

---

## 6. Hard constraints (violating one of these is a failed task, not a bug)

1. **Never send an order to a live IBKR port.** Allowed: `4002` (paper). Refused: `4001`, `7496`, `7497`. Assert it in code, do not merely document it.
2. **Never touch OANDA account `-014`.** Prove it: `lastTransactionID` unchanged before and after your full test run.
3. **Never `docker compose up -d` in the repo root.** It recreates `trading-live` and destroys the `/tmp` state of the deployed GBP_JPY strategies.
4. **Never modify** `LiveStrategyRunner.java`, `strategies/sqimported/**`, `config/live-config.json` risk values, or `ACCOUNT_RISK_CAP_PCT`.
5. **Every entry needs a protective stop, and it must be at the venue** (§2.3). A rejected or missing stop means flatten and halt. No software-only stop.
6. **Java 21.** GitHub CI compiles with `--release 21` even though the local toolchain may be newer. Do not use Java 22+ language features.
7. **Spotbugs is wired into the real build.** No dead private methods left behind.
8. **Commits:** `git -c commit.gpgsign=false commit`. Never `git add -A` (the running container writes into `data/runtime/`). Stage by path.
9. **Push every commit immediately**, on the branch and to the default branch. A local-only commit is not delivered work.
10. **Do not run a second Maven build while a deploy gate is running.**

---

## 7. Traps specific to this repository (each one already cost time to someone)

1. **The jar does not exist.** `docker/docker-compose.ibkr.yml` runs `java -jar trading-runtime.jar`. The `Dockerfile` ships unpacked classes in `/app/classes/<module>` plus third-party jars in `/app/libs`. That service could never start. Do not "fix" it by adding a jar; use the classpath the working entrypoint uses.
2. **`IbkrBroker` silently drops `stopLoss` and `takeProfit`.** See §2.3. If you place an entry and trust `order.stopLoss()`, you have a naked position and zero errors in the log.
3. **`BarSourceResolver` is not a live bar source.** It reads `.bars` files from disk. Using it for the runner means trading last year's bars today.
4. **Classes, not jars, prove deployment.** `docker exec trading-futures stat -c %y /app/classes/trading-runtime/com/martinfou/trading/runtime/ibkr/IbkrLiveRunner.class`, and grep the deployed classes for a string literal **your** change introduced.
5. **`Bar` timestamps are UTC `Instant`; IBKR bar times are exchange (America/New_York) time.** Convert once, at the socket boundary. `CmeGlobexMarketCalendar.EASTERN_ZONE` is the reference zone here.
6. **The still-forming bar.** At `:00:05` the bar that just closed is complete, but `reqHistoricalData` may also return a partial one. Drop it explicitly or the strategy evaluates an incomplete candle.
7. **Two connections, one clientId.** IBKR rejects a second connection on the same clientId with error 326. `IbkrBarSource` and the runner need distinct ids.
8. **`OrderTripwire` is fail-closed.** Without `TB_ALLOW_ORDERS=1` every order is refused, which looks exactly like a broken strategy.
9. **M30 vs hourly.** The MGC strategies are M30. An hourly scheduler silently halves their trade count; no error, just missing trades.
10. **`docker logs --since` parses its argument in LOCAL time** while the printed timestamps are UTC. Converting the wrong way returns the wrong hour.
11. **The deploy script's state restore only covers `/tmp`-era layouts.** A new lane uses the mounted state dir; do not copy files into `/tmp` of a container you are about to recreate.
12. **A strategy latch can survive a restart.** If a strategy reads "in trade" with no open position, that is a known defect class in this repo: log it and stop, do not paper over it.

---

## 8. Verification commands (copy-paste)

```bash
cd ~/projects/trading-bridge

# build + tests (Java 21 via mise)
export JAVA_HOME=$HOME/.local/share/mise/installs/java/26.0.1
export PATH="$JAVA_HOME/bin:$HOME/.local/share/mise/installs/maven/3.9.16/apache-maven-3.9.16/bin:$PATH"
./mvnw -B -DskipITs -Dspotbugs.skip=true test

# one module only, one class only (note the surefire- prefix)
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

1. `trading-futures` container running and healthy, with `trading-live` uptime unchanged.
2. Startup log shows: port check OK, MGC contract + expiry, account state, reconciliation result, strategy list, and a **next trigger timestamp ending in `:00:05`**.
3. At least one real M30 bar close processed unattended, with a log line naming the bar timestamp and the evaluation outcome.
4. One signal became an order whose fill is confirmed by `IbkrBroker.getPositions()`, **with a protective stop visible at the venue before the next bar**, and the order tagged `<strategy>_MGC`.
5. Restarting the container does not re-trade the same bar.
6. `TB_ALLOW_ORDERS=1` confirmed present in the container env; setting it to `0` produces a refusal (prove the guard is alive).
7. OANDA `-014` `lastTransactionID` identical before and after the whole exercise.
8. `./mvnw test` green; CI green on the branch and on the default branch.
9. Epic 46 status corrected (46-12).

---

## 10. Decisions (defaults pre-answered — Martin may override)

| # | Decision | Default if unopposed | Why |
|---|---|---|---|
| D1 | One MGC strategy or both? | **One first** (`-0-24542`, ELITE 96.0); add `-0-32921` only after the first loop is verified | Two independent reviewers argued one; the second is a config name, not design work |
| D2 | Separate compose project (`tb-futures`) or a `profiles:` entry in the main compose? | **Separate project** | An unqualified `up -d` must never start a second trading lane |
| D3 | Fix the scheduler in place or write a futures-specific scheduler? | **Fix in place** | The defects (hardcoded equity, empty positions, no `:00:05`) are bugs wherever it runs |
| D4 | Wire `CmeAutoRolloverManager` now? | **No.** Resolve front month at startup, log expiry, alert at T-3 days, roll by hand | Auto-rollover is its own risk surface; the MVP proves signal→fill first |
| D5 | How is the position protected, given §2.3? | **Extend the client with a stop-order path (46-8) and send a venue-side stop as the entry's sibling.** No software-only stop | A software stop dies with the JVM; Epic 46 exists precisely to avoid that |

If Martin overrides one, record it as the next free `D<n>` in `_bmad-output/planning-artifacts/prds/prd-ibkr-futures-2026-06-15/.decision-log.md`, the canonical decision log for this feature.

---

## 11. Non-goals, restated so nobody "improves" them

- No refactor of the OANDA runner, no shared abstraction between lanes in the MVP.
- No MES/MNQ/M2K/MCL.
- No full OCA bracket parity, no automated rollover, no ensemble weighting, no regime switching.
- No new dashboard page, no hub API change.
- No live account, at any point, for any reason.

---

## 12. Party Mode record (2026-10-29) — what changed and why

Three agents reviewed this brief after the first draft. Their responses are recorded verbatim, then what was adopted or rejected and on what evidence.

### 12.1 Winston (Architect) — verbatim

> **1. La décision centrale qui manque : un « composition root » IBKR.**
>
> Aucun des cinq composants n'est orphelin par accident — il leur manque l'hôte qui les instancie et les câble. Les faits le prouvent : `IbkrLiveRunner.java` n'existe pas, `LiveStrategyRunner` est câblé OANDA en dur, et `docker-entrypoint.sh` exige `OANDA_API_KEY`. La décision non prise, c'est **qui est le runner IBKR et que possède-t-il** : hébergement par un `IbkrLiveRunner` unique (composition root) ; **état de position** comme source de vérité autoritative, pas des `List.of()` passées par `BarCloseScheduler` (« `SmallAccountMarginGuard` est inutile tant qu'on lui ment sur les positions ») ; **mono-symbole** MGC M30 pour un premier jet (« inutile d'en lancer deux ») ; **barres** depuis une source live IBKR, pas `BarSourceResolver` qui ne sert que des `.bars`.
>
> **2. Sur-ingénierie à écarter pour du MGC paper à 1 micro-contrat :** `IbkrBracketOrderRouter` (« un ordre marché simple suffit »), `IbkrStateReconciler`, `IbkrGatewaySupervisor` (« le gateway tourne déjà, il n'a pas besoin de nounou »), `CmeAutoRolloverManager`, et les règles 50 % marge / 2 positions (« hors sujet à 1 micro »).
>
> **Ce que je ferais :** écrire un `IbkrLiveRunner` minimal — une stratégie MGC mono-symbole, ordres marché via un exécuteur léger, état de position en mémoire, barres IBKR live branchées sur `BarCloseScheduler` avec les *vraies* positions ouvertes ; archiver les cinq composants orphelins ; corriger `docker-entrypoint.sh` pour détecter IBKR et `docker-compose.ibkr.yml` pour lancer le vrai point d'entrée. Puis refléter la réalité dans le dashboard : Epic 46 n'est pas à 100 %.

### 12.2 Amelia (Dev) — verbatim

> **1. Plus petit chemin (signal MGC → fill IBKR paper).** Réutiliser le chemin déjà prouvé par `PaperMgcSmokeTest`. **Créer** `.../mgc/MgcSignalToIbkrRunner.java` : un main qui lit les bars MGC via `BarSourceResolver`, évalue un signal (crossover MME), résout le contrat puis `IbkrBroker.submitOrder(...)` en MARKET, en un seul passage pour prouver le fill. **Modifier** `BarCloseScheduler` : aligner sur :00:05, injecter l'equity réel via `getAccountState()` au lieu de `5000.0`, passer les vraies positions via `getPositions()`, un évaluateur distinct par symbole. **Modifier** `scripts/docker-entrypoint.sh` pour lancer le nouveau runner et supprimer l'exigence `OANDA_API_KEY`. `TB_ALLOW_ORDERS=1` obligatoire.
>
> **2. Edge cases qui cassent en premier :** `TB_ALLOW_ORDERS=1` absent (ordre avalé silencieusement par `OrderTripwire`) ; mauvais mois de contrat MGC ou symbole sans mois (rejet IBKR) ; equity en dur `5000.0` vs compte réel ; `List.of()` comme positions ouvertes (sizing faux, risque de double position) ; bar pas encore close à :00:05 (signal sur barre incomplète).
>
> **3. Je refuserais au premier jet :** `IbkrBracketOrderRouter`, `IbkrOrderExecutionManager`, `IbkrStateReconciler`, `CmeAutoRolloverManager`, `IbkrGatewaySupervisor`, la gestion multi-stratégies, et tout support OANDA/multi-broker — « c'est du bruit tant qu'un ordre unique n'est pas rempli ».

### 12.3 John (PM) — verbatim

> **1. Tranche minimale.** Un conteneur unique qui (a) consomme le gateway IBKR paper déjà vivant, (b) exécute **une seule** stratégie MGC (la 96.0, risque déjà configuré à 0.5 %), en paper, (c) journalise entrée/sortie/bracket natif OCA. Le rollover trimestriel, le bouclier de marge 50 %, la limite 2 positions et le superviseur de reconnexion : **hors scope explicite**, listés dans une section « Non-goals v1 ». Si une position paper MGC s'ouvre puis se clôt avec un bracket OCA correct, la lane est prouvée.
>
> **2. Type de document.** Un **brief d'implémentation**, pas une tech-spec : but et définition de done, contexte runtime exact, contrat I/O exact (identifiants instruments, symbole/expiry MGC M30), pièges connus, commandes de vérification, hors scope explicite.
>
> **3. Trois erreurs d'un agent externe :** toucher la lane FX vivante (`-014`) ; croire Epic 46 fini et « brancher » les 5 composants orphelins ; s'attaquer aux brackets CME ou au rollover.

### 12.4 What was adopted, rejected, and why

| Item | Verdict | Evidence / reason |
|---|---|---|
| One strategy for the first loop (Winston, John) | **Adopted** (D1 changed) | Two independent voices; the second strategy is a config name, not design work |
| Stage 0: prove the fill with the existing smoke test first (Amelia) | **Adopted** | `PaperMgcSmokeTest` already exists; zero code to validate the gateway |
| Scheduler fixes: `:00:05`, real equity, real positions, per-symbol evaluator (all three) | **Adopted** (46-7) | Verified in source at lines 74-77, 139, 114-116, 30 |
| Live IBKR bar source rather than `BarSourceResolver` (Winston) | **Adopted** (46-9) | Verified: `BarSourceResolver.load(...)` reads `.bars` files |
| Amelia's `BarSourceResolver` as the live bar source | **Rejected** | Factually wrong on this repo; it would trade historical data. Kept as trap #3 |
| Amelia's `MgcSignalToIbkrRunner` with an invented "EMA crossover" signal | **Rejected** | The deliverable is to run the *existing ELITE strategy*, not to author a new signal |
| Winston and Amelia: modify `scripts/docker-entrypoint.sh` | **Rejected** | It is the live OANDA lane's entrypoint and hard-fails without OANDA credentials. A second entrypoint costs one file; breaking the live lane costs a deployment |
| Winston: archive the 5 orphaned components as over-engineering | **Partially rejected** | `IbkrBracketOrderRouter` is needed the moment a stop must exist, and §2.3 proves a stop must exist. `IbkrStateReconciler` / `IbkrGatewaySupervisor` are cheap and already tested. `CmeAutoRolloverManager` stays unwired (D4) |
| Winston: "a plain market order is enough" | **Rejected on measurement** | §2.3: `IbkrBroker` rejects everything but MARKET *and drops the stop fields*, so a market-only MVP produces an unprotected position. This finding created 46-8 |
| John: verify a native OCA bracket as the proof | **Adopted as intent, narrowed as mechanism** | The MVP sends entry + venue stop built by `IbkrBracketOrderRouter`; full OCA parity stays a follow-up (§11) |
| John: an explicit "Non-goals v1" section is mandatory | **Adopted** (§11) | Already present, kept explicit |
