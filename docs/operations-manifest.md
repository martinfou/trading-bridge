# Operations Manifest — Product Perspective

> **Author:** John PM (Product Manager)  
> **Context:** Following Epics N through N+6 (paper trading reliability), this document defines the **operational layer** — runbooks, dashboards, reviews, procedures — that a trader/operator needs to confidently run paper and live trading on Trading Bridge.  
> **Not about code features.** This is about trust, observability, process, and proving the platform works.  
> **Companion docs:** [`prop-shop-runbook.md`](prop-shop-runbook.md) (paper→LIVE promote flow), [`testing.md`](testing.md) (technical gate details), [`deployment-guide.md`](deployment-guide.md) (infra).

---

## Table of Contents

1. [The Operator's Mental Model](#1-the-operators-mental-model)
2. [Dashboard Views (What to Watch)](#2-dashboard-views-what-to-watch)
3. [Pre-Flight Checklist (Before Any Paper Run)](#3-pre-flight-checklist-before-any-paper-run)
4. [Start-of-Day Protocol](#4-start-of-day-protocol)
5. [Incident Response Flow](#5-incident-response-flow)
6. [Weekly Review Process](#6-weekly-review-process)
7. [Monthly Review Process](#7-monthly-review-process)
8. [Proving "The Platform Works"](#8-proving-the-platform-works)
9. [Emergency Procedures](#9-emergency-procedures)
10. [Post-Mortem Template](#10-post-mortem-template)
11. [Operational Calendar](#11-operational-calendar)
12. [Runbook Index](#12-runbook-index)

---

## 1. The Operator's Mental Model

Before we define procedures, define how the operator should think about the platform.

### 1.1 Three Layers of Trust

```
┌──────────────────────────────────┐
│  LAYER 3: PORTFOLIO HEALTH       │  "Am I making/losing money overall?"
│  - Equity curve, total P&L       │
│  - Portfolio drawdown            │
│  - Capital allocation            │
├──────────────────────────────────┤
│  LAYER 2: STRATEGY HEALTH        │  "Is each strategy behaving as expected?"
│  - Paper vs backtest drift       │
│  - Reconciliation alerts         │
│  - Promote readiness             │
├──────────────────────────────────┤
│  LAYER 1: PLATFORM HEALTH        │  "Is the software running correctly?"
│  - Control plane is up           │
│  - Broker connections are live   │
│  - No stale runs                 │
│  - Event store is accepting      │
└──────────────────────────────────┘
```

**Principle:** Always check bottom-up. If Layer 1 is red, Layers 2 and 3 are unreliable. Fix the platform first, then assess strategies, then review portfolio.

### 1.2 Green/Yellow/Red Definition

| Status | Meaning | What to do |
|--------|---------|------------|
| ✅ **Green** | All signals nominal. No intervention needed. | Proceed normally. |
| 🟡 **Yellow** | Degraded / Attention needed. Non-critical issue or approaching threshold. | Investigate within current session. Do not promote. |
| 🔴 **Red** | Platform or strategy is in a failure state. Capital at risk or data integrity compromised. | Immediate action. Stop trading if live. Follow incident flow. |

---

## 2. Dashboard Views (What to Watch)

The operator needs **five canonical views**. These define what a dashboard should render, regardless of whether it's the TUI, the Laravel control room, or the Electron desktop app.

### 2.1 View 1: Control Summary (Layer 1 — "Is it running?")

**Purpose:** At-a-glance platform health. This is the `GET /control/summary` endpoint.

| Signal | What it tells you | Alert threshold |
|--------|------------------|-----------------|
| `freshness.staleRunCount` | Number of runs that haven't heartbeated | > 0 🟡 |
| `freshness.secondsSinceLastEvent` | How long since any event was recorded | > 300s 🟡, > 600s 🔴 |
| `runs[].isStale` | Per-run staleness flag | Any `true` 🟡 |
| `runs[].gaps[]` | Data gaps in run sequence | Any gaps 🟡 |
| `runs[].dailyDdBreached` | Daily drawdown limit hit | Any `true` 🔴 |
| `executionLabelCatalog` | Shows which labels are active (BACKTEST, PAPER_OANDA, LIVE_OANDA etc.) | Used for filtering |
| `signals.stale[]` | Stale runs summary | Any 🔴 |
| `signals.gaps[]` | Gap summary | Any 🟡 |
| `signals.drift[]` | Strategy drift (broker runs only) | `PAUSE` recommendation 🔴 |

**Operator rule:** If this view is red, do not trade. Fix platform first.

### 2.2 View 2: Strategy Catalog (Layer 2 — "What's running?")

**Purpose:** Every strategy, its deployment status, current run, and promote readiness.

| Column | Source | Notes |
|--------|--------|-------|
| Strategy ID | StrategyCatalog | |
| Deployment status | Deployments | None / BACKTEST / PAPER_STUB / PAPER_OANDA / LIVE_OANDA |
| Current run ID | Control summary runs[] | Link to View 3 |
| Execution label | Run.executionLabel | |
| Promote readiness | `GET /api/strategies/{id}/promote-readiness` | Ready / Not ready + reasons |
| Reconciliation | `reconciliation.clear` | Check mark or alert count |
| Daily DD | `dailyDdBreached` | Green / Red |
| Days on paper | `paperElapsedDays` / `paperDaysRequired` | Progress bar |
| Drift signal | `signals.drift[]` | HOLD / REVIEW_PARAMS / PAUSE |

**Operator rule:** Check this view weekly (see §6). Only promote strategies that are green across all columns.

### 2.3 View 3: Run Detail (Layer 2 — "What happened?")

**Purpose:** Deep dive into a specific run. Events, trades, reconciliation, evidence export.

| Section | Endpoint / Source | What to look for |
|---------|-------------------|------------------|
| Metadata | `GET /api/runs/{runId}` | Mode, executionLabel, strategyId, status |
| Events timeline | `GET /api/runs/{runId}/events` | Chronological: ORDER_SUBMITTED → FILL → REJECT → HEARTBEAT → RUN_ENDED |
| Trade log | Events filtered by FILL | Entry/exit, size, price, P&L |
| Reconciliation | `RECONCILIATION_ALERT` events | Any broker/journal divergence |
| Evidence export | `GET /api/runs/{runId}/export` | JSONL for machine review |
| HTML report | `GET /api/runs/{runId}/export?format=html` | Human-readable due diligence |

**Operator rule:** Before promoting a run, review its trade log for anomalous fills, unexpected rejections, or reconciliation alerts.

### 2.4 View 4: Risk Dashboard (Layer 3 — "Am I safe?")

**Purpose:** Aggregate risk exposure across all strategies.

| Metric | Source | Notes |
|--------|--------|-------|
| Total portfolio equity | Sum broker account + paper equity | |
| Daily P&L | Equity change since UTC midnight | |
| Portfolio drawdown | Peak-to-trough across all runs | |
| Max exposure | Sum of all open position notional | |
| Strategy-level DD | Per-run maxDrawdownPct | Compare to promote-gates.json threshold |
| Daily DD breaches | Any `DAILY_DD_BREACH` events today | |
| Kill switch status | Per-strategy `killSwitchActive` | |

**Operator rule:** If portfolio drawdown exceeds 10%, pause all promotions. If any strategy hits max daily DD, review that strategy before next trading day.

### 2.5 View 5: Weekly/Monthly Review (Layers 2+3 — "Am I improving?")

**Purpose:** Long-term performance trends. Not real-time. Used in reviews (§6, §7).

| Report | What it aggregates | Period |
|--------|-------------------|--------|
| Strategy P&L | P&L per strategy per week | Weekly |
| Drift trends | Drift signal changes over time | Weekly |
| Reconciliation summary | Alert count by strategy | Weekly |
| Promote log | Which strategies promoted, rejected | Monthly |
| Portfolio correlation | P&L correlation between strategies | Monthly |
| Win rate / Sharpe by strategy | Performance consistency | Monthly |
| Incident log | All incidents, root causes, resolutions | Monthly |

---

## 3. Pre-Flight Checklist (Before Any Paper Run)

Before starting a **new** paper run (promoting BACKTEST → PAPER_OANDA), verify these items in order:

### 3.1 Platform Health Check

- [ ] Control plane is responding: `curl http://localhost:8080/control/summary`
- [ ] No stale runs from previous sessions: `freshness.staleRunCount == 0`
- [ ] Event store (SQLite) is accessible: no disk full errors in logs
- [ ] Broker credentials are valid: `GET /api/broker-accounts` returns expected accounts
- [ ] OANDA practice account balance is sufficient (> $10,000 recommended for meaningful paper trades)
- [ ] `data/runtime/promote-gates.json` thresholds are correct for current strategy profile
- [ ] `data/runtime/risk-limits.json` limits are set (maxPositionSize, maxOpenExposure, maxDailyDrawdownPct)
- [ ] Clock is synchronized (NTP): paper timestamps must align with broker timestamps

### 3.2 Backtest Integrity Check

- [ ] Source backtest passed all promote gates: `GET /api/strategies/{id}/promote-readiness`
- [ ] `golden_baseline` gate passed (if applicable)
- [ ] Backtest trade log reviewed — no suspicious fills, no lookahead bias
- [ ] Backtest used same symbol/bar-config that paper will run on
- [ ] `validation_module` gate passed (if OOS holdout or execution stress enabled)
- [ ] Backtest is on the **same code version** that will execute paper (no uncommitted changes)

### 3.3 Risk Pre-Check

- [ ] Strategy's max DD in backtest is ≤ 50% of risk limit (safety margin)
- [ ] If strategy uses high leverage, confirm `maxPositionSize` limits are appropriate
- [ ] No economic news blackout in the next 2 hours (NFP, FOMC, CPI — see economic calendar)
- [ ] No other strategy on the same symbol has a conflicting position (correlation check)
- [ ] Current portfolio drawdown is < 50% of max allowed

### 3.4 Paper-Specific

- [ ] Target execution label is `PAPER_OANDA` (not `PAPER_STUB` — stub doesn't count toward 30-day period)
- [ ] Broker account ID is correct: practice account, not live
- [ ] Paper period timer will start now — 30 calendar days until eligible for LIVE
- [ ] Kill switch is NOT active on this strategy
- [ ] Understanding: paper runs test broker connectivity, reconciliation, and real-world fills — they **will** diverge from backtest

### 3.5 Go / No-Go

| Result | Action |
|--------|--------|
| All checks pass | ✅ Promote to PAPER_OANDA |
| Yellow items (risk, correlation) | 🟡 Proceed with caution — set smaller position size |
| Any Red item | 🔴 Do NOT promote. Fix the issue first. |

---

## 4. Start-of-Day Protocol

**When:** Each trading day before market open (or before the first run of the day).  
**Duration:** 5-10 minutes.  
**Applies to:** Any day with active runs (paper or live).

### 4.1 Morning Routine (5 min)

```
┌──────────────────────────────────────────────┐
│  1. Control Summary (30s)                     │
│     - Any red signals?                        │
│     - Any stale runs overnight?               │
│     - Any gaps in event sequence?             │
├──────────────────────────────────────────────┤
│  2. Active Run Status (1 min)                 │
│     - Every PAPER_OANDA / LIVE_OANDA run:     │
│       → Status (RUNNING / PAUSED / ENDED)     │
│       → Last heartbeat timestamp              │
│       → Daily DD % vs limit                   │
├──────────────────────────────────────────────┤
│  3. Reconciliation Check (1 min)              │
│     - Any RECONCILIATION_ALERT events         │
│       since last check?                       │
│     - If yes, quick review of divergence      │
├──────────────────────────────────────────────┤
│  4. Kill Switch Audit (30s)                   │
│     - No kill switches accidentally active    │
│     - Unless intentional from previous day    │
├──────────────────────────────────────────────┤
│  5. Log Review (1 min)                        │
│     - Scan last 50 lines of log output        │
│     - Look for ERROR / WARN entries           │
│     - Broker reconnection events?             │
├──────────────────────────────────────────────┤
│  6. Decision Log (30s)                        │
│     - Note any anomalies found                │
│     - Date-stamp entry                        │
└──────────────────────────────────────────────┘
```

### 4.2 End-of-Day Ritual (3 min)

- [ ] Record today's P&L for each active strategy in the weekly tracker
- [ ] Note any manual adjustments (killed a run, changed limits)
- [ ] Check that no run was left in a hanging state (RUNNING with no recent heartbeat)
- [ ] If LIVE trades were made, review fills against expected signals
- [ ] Prepare one-sentence summary for the day's log

### 4.3 What "Green" Looks Like at Start of Day

```
control/summary response:
  freshness:
    staleRunCount: 0
    secondsSinceLastEvent: < 300
  runs:
    - all RUNNING runs have heartbeats within the last 2 bar intervals
    - no dailyDdBreached: true
    - no gaps detected
  signals:
    stale: []
    gaps: []
    drift: [] or HOLD recommendation only
```

---

## 5. Incident Response Flow

### 5.1 Severity Matrix

| Severity | Label | Response time | Example |
|----------|-------|---------------|---------|
| **S1** | 🔴 CRITICAL | ≤ 5 minutes | Broker disconnect on LIVE run. Reconciliation divergence > 5%. Control plane down. Equity gap > 10%. |
| **S2** | 🟡 MAJOR | ≤ 30 minutes | Stale PAPER run. Reconciliation alert (minor). Data gap > 1 hour. Backtest results mismatch. |
| **S3** | 🔵 MINOR | ≤ 24 hours | Single HEARTBEAT miss. Non-critical log ERROR. Configuration drift. |
| **S4** | ⚪ INFO | Next review | New warning pattern. Cosmetic UI issue. Feature request disguised as bug. |

### 5.2 Generic Incident Response Steps

For any incident, follow this sequence. Do NOT skip steps.

```
STEP 1: DETECT
─────────────────────────────────────────────────────────
Signal arrives via:
  - Dashboard alert (red/yellow indicator)
  - Log scanner (ERROR level)
  - Notification (Telegram / Discord / systemd)
  - Routine check (morning review, pre-flight)
  → ACKNOWLEDGE THE INCIDENT. Note the timestamp.

STEP 2: TRIAGE (≤ 2 min)
─────────────────────────────────────────────────────────
  a. What is affected? (Platform / Strategy / Broker / Data)
  b. What severity? (S1-S4 — use severity matrix)
  c. Is capital at risk RIGHT NOW?
  d. Does this affect other strategies or only one?
  → DECIDE: Act now or investigate first?

STEP 3: CONTAIN (if S1/S2) (≤ 5 min)
─────────────────────────────────────────────────────────
  Possible containment actions (in order of escalation):
    1. Kill the affected strategy:
       curl -X POST .../kill -d '{"actor":"operator","reason":"..."}'
    2. Pause all broker runs if platform-level issue
    3. Close positions if reconciliation divergence > threshold
    4. Stop the control plane if data integrity compromised
  → CONTAIN FIRST, INVESTIGATE SECOND.

STEP 4: INVESTIGATE
─────────────────────────────────────────────────────────
  Sources of truth (in order):
    1. Event store: GET /api/runs/{runId}/events
    2. Run export: GET /api/runs/{runId}/export
    3. Application logs: journalctl / log files
    4. Broker API: Check OANDA/IBKR directly for position
    5. Reconciliation: Compare broker vs journal state
  → ROOT CAUSE identified? Document it.

STEP 5: RESOLVE
─────────────────────────────────────────────────────────
  Apply fix:
    - If code bug: git revert / push fix
    - If config error: correct thresholds / credentials
    - If transient: restart run or control plane
    - If broker issue: file support ticket
  → VERIFY the fix: re-run checks from step 1

STEP 6: POST-MORTEM (within 48h for S1, within 1 week for S2)
─────────────────────────────────────────────────────────
  - Write post-mortem using template (§10)
  - File follow-up issues in sprint backlog
  - Update runbook if gap identified
```

### 5.3 Specific Incident Runbooks

#### 5.3.1 Stale Run Detected (S2 initially, escalates to S1 if LIVE)

```
TRIGGER: control/summary shows isStale: true for a RUNNING run
         staleness defined as no HEARTBEAT in > runningStaleThresholdSeconds

IF executionLabel is LIVE_OANDA or LIVE_IBKR → S1
IF executionLabel is PAPER_OANDA            → S2
IF executionLabel is PAPER_STUB or BACKTEST → S3

STEPS:
  1. Check last HEARTBEAT timestamp: GET /api/runs/{runId}/events?type=HEARTBEAT
  2. Check broker directly: Is the run still active on OANDA/IBKR?
  3. Check control plane logs: Any uncaught exception?
  4. If run is actually dead but events not written:
     a. For LIVE: Kill strategy immediately. Manual position check.
     b. For PAPER: Resume or restart the run.
  5. If control plane crashed:
     a. Restart: mvn exec:java -pl trading-runtime ...
     b. Verify event store integrity: any missing events?
  6. Extend stale threshold if bar interval > 2 min (configure in stale-thresholds.json)
```

#### 5.3.2 Reconciliation Alert (S2, S1 if LIVE + persistent)

```
TRIGGER: RECONCILIATION_ALERT event on a broker-backed run

STEPS:
  1. GET /api/runs/{runId}/events?type=RECONCILIATION_ALERT
  2. Review divergences[]:
     - brokerQuantity vs journalQuantity
     - Which symbol(s)
  3. Determine cause:
     a. Timing issue (fill not yet journaled) → Won't fix, monitor
     b. Partial fill not reflected → Manual correction needed
     c. Broker rejected order but journal shows FILL → Critical data corruption
  4. If brokerQuantity > journalQuantity for LIVE → Kill switch immediately
  5. If journalQuantity > brokerQuantity → Likely a fill that broker hasn't confirmed
  6. Log the divergence in the daily log. Track recurrence.
```

#### 5.3.3 Daily Drawdown Breach (S2)

```
TRIGGER: run transitions to PAUSED with action=DAILY_DD_BREACH

STEPS:
  1. Don't panic. The risk engine auto-paused the run. Capital is protected.
  2. Review equity curve: Was it a single bad bar or sustained drawdown?
  3. Check market conditions: News event? Volatility spike?
  4. Review risk limits:
     - Is maxDailyDrawdownPct too tight for this strategy's volatility?
     - Or was the strategy genuinely over-leveraged?
  5. Decision:
     a. Adjust limits (if too tight) and resume
     b. Keep paused and investigate strategy quality
     c. Kill the deployment if strategy is broken
  6. For LIVE runs: Review BEFORE next trading day. Do not resume same day.
```

#### 5.3.4 Broker Disconnect (S1 for LIVE, S2 for PAPER)

```
TRIGGER: Control plane logs show broker API errors / timeouts
         OR run status shows no fills but bars keep coming

STEPS:
  1. Is it OANDA or IBKR?
     OANDA: Check api-fxpractice.oanda.com / api-fxtrade.oanda.com status
     IBKR:  Check TWS/IB Gateway process on the machine
  2. For OANDA:
     - Rate limit hit? Wait 1 minute, retry
     - API key expired? Rotate credentials
     - Account deactivated? Check OANDA dashboard
  3. For IBKR:
     - Gateway process running? ps aux | grep java
     - Port listening? netstat -an | grep 7497
     - Client ID conflict? Another instance connected?
  4. Auto-reconnect mechanism should handle this. If not:
     - Kill affected runs
     - Restart broker adapter (restart control plane if needed)
     - Restart runs after connectivity confirmed
  5. For LIVE: If broker was down > 5 minutes → Close all positions via broker web UI
```

#### 5.3.5 Promote Gate Failure (S3)

```
TRIGGER: POST /api/strategies/{id}/promote returns HTTP 422

This is expected — gates are designed to prevent bad promotions.

STEPS:
  1. Read failing gates from response body `checks[]`
  2. Common failures:
     - golden_baseline: Backtest metrics drifted. Re-capture baseline.
     - min_trades: Strategy produced too few trades. Check bar count.
     - max_drawdown_pct: Backtest DD exceeded threshold. Review strategy.
     - paper_duration_days: < 30 days on PAPER_OANDA. Wait. Stub doesn't count.
     - reconciliation.clear: unresolved divergence. Fix reconciliation.
  3. Fix the root cause or adjust thresholds (conscious decision)
  4. Retry promote
```

---

## 6. Weekly Review Process

**When:** Every Sunday (or last day of trading week).  
**Duration:** 20-30 minutes.

### 6.1 Weekly Checklist

```
WEEK OF: YYYY-MM-DD
REVIEWER: [name]

[ ] 1. PLATFORM HEALTH (Layer 1)
     [ ] Review this week's incident log
     [ ] Any unresolved S3/S4 issues? File or close.
     [ ] Check disk usage: df -h (SQLite DB growth)
     [ ] Check log rotation: logs/ directory size
     [ ] Verify NTP sync: timedatectl status
     [ ] Confirm broker API keys not expiring soon

[ ] 2. STRATEGY HEALTH (Layer 2)
     [ ] For each PAPER_OANDA strategy:
         → Paper elapsed days (progress to 30)
         → Reconciliation alert count (trending up?)
         → Drift signal (any changes this week?)
         → Daily DD this week (max value + date)
     [ ] For each LIVE strategy:
         → Same as above, PLUS:
         → P&L this week (nominal + %)
         → Trade count this week
         → Win rate this week
     [ ] For each BACKTEST-only strategy:
         → Any ready to promote to paper?
         → Run promote-readiness check

[ ] 3. PORTFOLIO HEALTH (Layer 3)
     [ ] Total P&L (week + month-to-date)
     [ ] Portfolio drawdown (current + max this week)
     [ ] Correlation check: any two strategies both had bad weeks?
     [ ] Capital allocation review: weight adjustments needed?

[ ] 4. RISK REVIEW
     [ ] Risk limits still appropriate?
     [ ] Any near-miss incidents this week?
     [ ] Max exposure vs limits this week
     [ ] Review economic calendar for next week
     [ ] Note upcoming high-impact events (NFP, FOMC, CPI)

[ ] 5. PROMOTE DECISIONS
     [ ] Any strategy ready for PAPER → LIVE promote?
     [ ] Any BACKTEST → PAPER candidates?
     [ ] Review decision log from this week

[ ] 6. ACTION ITEMS
     [ ] File issues found during review
     [ ] Update configuration if thresholds need adjustment
     [ ] Schedule any follow-up investigations
```

### 6.2 Weekly Metrics Log

Track these metrics week-over-week in a spreadsheet or journal:

| Week | Strategies Active | Total Trades | Win Rate | P&L | Max DD | Reconciliation Alerts | Incidents |
|------|------------------|-------------|----------|-----|--------|----------------------|-----------|
| ...  |                  |             |          |     |        |                      |           |

---

## 7. Monthly Review Process

**When:** First day of each month.  
**Duration:** 1-2 hours. Requires data from all 4+ weeks.

### 7.1 Monthly Checklist

```
MONTH OF: YYYY-MM

[ ] 1. PERFORMANCE REPORT (required reading)
     [ ] P&L attribution: Which strategies made/lost money?
     [ ] Sharpe ratio per strategy (monthly + since inception)
     [ ] Win rate trend (rolling 20 trades per strategy)
     [ ] Average win vs average loss (is expectancy positive?)
     [ ] Best/worst trade of the month (case study)

[ ] 2. DRIFT ANALYSIS
     [ ] For each PAPER → LIVE candidate:
         → 30-day paper period complete?
         → Drift within acceptable range?
         → Reconciliation: 0 persistent alerts?
         → Promote-readiness: all gates green?
     [ ] For each LIVE strategy:
         → Backtest vs live drift: is actual performance tracking expected?
         → If drift exceeds 2 standard deviations: escalate

[ ] 3. PROMOTE DECISIONS (this month's actions)
     [ ] Strategies promoted to PAPER this month
     [ ] Strategies promoted to LIVE this month
     [ ] Strategies killed / retired this month
     [ ] Rationale for each decision

[ ] 4. RISK LIMIT REVIEW
     [ ] Current risk limits vs actual usage
     [ ] Any limits that were hit too often (too tight) or never (too loose)
     [ ] Adjustments for next month
     [ ] Overall portfolio VaR assessment

[ ] 5. PLATFORM HEALTH REPORT
     [ ] Uptime this month (%)
     [ ] Incidents this month (count by severity)
     [ ] Mean time to detect (MTTD) — how fast did we notice?
     [ ] Mean time to resolve (MTTR) — how fast did we fix?
     [ ] Recurring incident patterns

[ ] 6. DOCUMENTATION AUDIT
     [ ] Any runbooks that need updating based on this month's incidents?
     [ ] Any new operational procedures needed?
     [ ] Are the pre-flight and start-of-day checklists still accurate?

[ ] 7. NEXT MONTH PLANNING
     [ ] Which strategies to focus on
     [ ] Any planned code changes that affect operations
     [ ] Capacity planning (disk, memory, broker API limits)
     [ ] Scheduled maintenance windows
```

### 7.2 Platform Health Score (Monthly)

Calculate this score to objectively measure "is the platform getting better?"

| Metric | Weight | How to measure |
|--------|--------|----------------|
| Uptime | 20% | % of trading hours with green control summary |
| Incident count | 20% | Inverse of total incidents (fewer = better) |
| MTTR (Mean Time to Resolve) | 15% | Average minutes from detection to resolution |
| Reconciliation rate | 15% | % of runs with zero reconciliation alerts |
| Promote success rate | 15% | % of promote attempts that passed all gates (first try) |
| Data integrity | 15% | % of runs with complete event sequences (no gaps) |

**Target:** Score > 85/100 to be "production trustworthy."

---

## 8. Proving "The Platform Works"

This section answers the operator's deepest question: **"How do I know I can trust this system with real money?"**

### 8.1 The Proof Pyramid

```
                         ┌─────────────────────────┐
                         │  I trust it with LIVE   │
                         │  capital ($)             │
                         ├─────────────────────────┤
                         │  Paper matches backtest  │
                         │  within drift tolerance  │
                         ├─────────────────────────┤
                         │  Broker reconciliation   │
                         │  is consistent           │
                         ├─────────────────────────┤
                         │  Platform runs 24/7      │
                         │  without intervention    │
                         ├─────────────────────────┤
                         │  Golden backtest         │
                         │  reproduces every time   │
                         ├─────────────────────────┤
                         │  Unit & integration      │
                         │  tests pass              │
                         └─────────────────────────┘
```

### 8.2 Five Proofs

#### Proof 1: Reproducibility

```
GIVEN: mvn test -pl trading-examples -Dtest=GoldenBacktestTest
WHEN:  I run it on any machine with the same code version
THEN:  The CI subset test passes with exact metrics (± tolerance)
→ This proves the backtest engine is deterministic and correct.
```

**Run this:** Before every promote, after every code change, weekly as a sanity check.

#### Proof 2: Paper-Backtest Parity

```
GIVEN: A strategy deployed on PAPER_OANDA for 30 days
WHEN:  I compare paper equity curve to backtest equity curve (same symbol, same bar config)
THEN:  The curves should not diverge beyond drift thresholds
→ This proves the execution engine matches simulation for the market conditions seen.
```

**Run this:** At 15 days and 30 days of paper observation. Use drift signals as the automated check.

#### Proof 3: Reconciliation Integrity

```
GIVEN: A broker-backed run (PAPER_OANDA or LIVE_OANDA)
WHEN:  After each bar, BrokerRunExecutor reconciles broker vs journal
THEN:  Zero RECONCILIATION_ALERT events → broker and journal are in sync
→ This proves the event store accurately reflects broker state.
```

**Track this:** Reconciliation alert count should be **0** for all current runs. Any non-zero count is a yellow flag.

#### Proof 4: Platform Uptime Challenge

```
GIVEN: The control plane is started
WHEN:  Left running for 7 days without restart
THEN:  At the end of 7 days:
       - All completed runs have clean RUN_ENDED events
       - No unexpected crashes in logs
       - Event store integrity check passes
→ This proves the platform can run unattended.
```

**Run this:** Once per month. Start a long-running paper strategy and leave it for 7 days. Review logs day 7.

#### Proof 5: Promote Gate Rigor

```
GIVEN: A strategy passes all promote gates to go LIVE
THEN:  The gates have verified:
       - Backtest integrity (golden_baseline)
       - Paper observation (30 days)
       - Drift within tolerance
       - Reconciliation clear
       - Risk limits respected
→ The promote gate system itself is the proof that due diligence was done.
```

**Note the audit trail:** Every promote attempt is journaled. Every gate result is recorded. You can prove *to anyone* that due diligence was performed.

### 8.3 One-Page Health Certificate

Before putting any real capital at risk, complete this certificate:

```
┌─────────────────────────────────────────────────────┐
│           PLATFORM HEALTH CERTIFICATE                │
│                                                     │
│ Date: ______________    Version: ______________      │
│                                                     │
│ [ ] Golden backtest reproduces (mvn test)            │
│ [ ] No stale runs in control summary                 │
│ [ ] No reconciliation alerts on current runs         │
│ [ ] Broker connectivity confirmed (OANDA/IBKR)       │
│ [ ] Risk limits are set and appropriate              │
│ [ ] 7-day uptime challenge passed (this month)       │
│ [ ] Monthly review completed                         │
│ [ ] All promote gates pass for target strategies     │
│                                                     │
│ SIGNATURE: ____________________                      │
└─────────────────────────────────────────────────────┘
```

---

## 9. Emergency Procedures

### 9.1 Kill Switch Protocol

**When to use:** Uncontrolled drift, reconciliation divergence > 5%, broker malfunction, any situation where you cannot explain what the strategy is doing.

```bash
# Step 1: Kill the strategy immediately
curl -X POST http://localhost:8080/api/strategies/{strategyId}/kill \
  -H 'Content-Type: application/json' \
  -d '{"actor":"operator","reason":"[BRIEF REASON — e.g. reconciliation divergence > 5%]"}'

# Step 2: Verify kill was accepted (HTTP 202)
# Step 3: Check positions directly on broker (OANDA web UI / IBKR TWS)
# Step 4: Close any open positions manually if needed
# Step 5: Document the kill in the incident log
```

**After a kill:** The strategy is blocked from new orders. It will NOT auto-restart. You must manually investigate and decide to re-deploy or archive.

### 9.2 Emergency Stop (Hard Shutdown)

**When to use:** Platform-level malfunction — event store corruption, runaway memory, infinite loop.

```bash
# Step 1: Stop the control plane
# (Ctrl+C or systemctl stop)

# Step 2: Close all positions via broker web UI
# Do NOT rely on the platform if it's corrupted

# Step 3: Back up event store
cp data/runtime/events.db data/runtime/events.db.emergency-backup-$(date +%Y%m%d_%H%M%S)

# Step 4: Investigate root cause before restarting
# Step 5: Restart and verify control summary is green
```

### 9.3 Position Reconciliation Emergency

**When to use:** Broker shows positions that don't match journal.

```
1. DO NOT TRADE until reconciled.
2. Compare broker positions (UI) vs journal (GET /api/runs/{runId}/events?type=FILL)
3. If divergence is due to system error:
   - Close broker positions manually
   - Clear journal entries for the affected run
   - Re-deploy the strategy fresh
4. If divergence is due to partial fills or timing:
   - Log the divergence
   - Adjust journal to match broker (manual entry in event store)
   - Continue monitoring
5. For LIVE: Document in incident log. Review before next trading day.
```

---

## 10. Post-Mortem Template

Complete this within 48 hours for S1 incidents, 1 week for S2.

```
# POST-MORTEM: [INCIDENT TITLE]

## Metadata
- Date: YYYY-MM-DD
- Severity: S1 / S2 / S3
- Duration: [start time] → [end time] ([duration])
- Reporter: [name]
- Strategy affected: [strategy ID(s)] / Platform-wide

## Summary
[One paragraph. What happened? What was the impact?]

## Timeline
| Time (UTC) | Event |
|------------|-------|
|            | First signal detected |
|            | Triage decision |
|            | Containment action |
|            | Root cause identified |
|            | Resolution applied |
|            | Verification complete |

## Root Cause Analysis
[What went wrong? Technical explanation.]

## Impact Assessment
- Capital at risk: $[amount]
- Actual loss (if any): $[amount]
- Data integrity affected: Yes/No
- Other strategies affected: Yes/No

## What Worked
[What did the platform handle well?]

## What Failed
[What did the platform NOT handle well?]

## Action Items
| # | Action | Owner | Due | Status |
|---|--------|-------|-----|--------|
| 1 |        |       |     | [ ]    |
| 2 |        |       |     | [ ]    |

## Prevention
[How do we prevent this from happening again?]
[How do we detect it faster next time?]

## Lessons Learned
[What did we learn about operating this platform?]
[Should we update a runbook?]
```

---

## 11. Operational Calendar

### Daily (5 min)
- [ ] Start-of-day protocol (§4)
- [ ] End-of-day ritual (§4.2)

### Weekly (20-30 min, Sunday)
- [ ] Weekly review checklist (§6)
- [ ] Update weekly metrics log

### Biweekly (30 min)
- [ ] Promote readiness check for all PAPER strategies
- [ ] Review drift signals on PAPER runs

### Monthly (1-2 hours, Day 1)
- [ ] Monthly review checklist (§7)
- [ ] Platform health score calculation
- [ ] Platform health certificate (if going LIVE)

### Per-Promote (variable)
- [ ] Pre-flight checklist (§3)
- [ ] Post-promote verification (check control summary 1 hour after)

### After Any Incident
- [ ] Incident response flow (§5)
- [ ] Post-mortem (within 48h for S1) (§10)

### Quarterly (2-4 hours)
- [ ] Full documentation audit
- [ ] Risk limit strategy review
- [ ] Backtest golden baseline re-capture
- [ ] Disaster recovery drill

---

## 12. Runbook Index

| Runbook | Location | Covers |
|---------|----------|--------|
| **Prop-shop runbook** | `docs/prop-shop-runbook.md` | Paper→LIVE promotion, daily review in PAPER period, API reference |
| **Operations manifest** | `docs/operations-manifest.md` | THIS DOC — all operational procedures |
| **Testing & gates** | `docs/testing.md` | Golden backtest, promote gates, reconciliation, kill switch, drift, control summary |
| **Deployment guide** | `docs/deployment-guide.md` | Docker deploy, VPS setup, environment variables |
| **Architecture** | `docs/architecture.md` | System architecture, module layout |
| **Pipeline playbook** | `docs/pipeline-playbook.md` | Strategy generation pipeline (LLM pipeline) |
| **Incident post-mortems** | `docs/post-mortems/` | *To be created per incident* |

### Quick Reference Card (printable)

```
╔══════════════════════════════════════════════════════════╗
║            TRADING BRIDGE — QUICK REFERENCE              ║
╠══════════════════════════════════════════════════════════╣
║                                                          ║
║  CONTROL SUMMARY     curl localhost:8080/control/summary  ║
║  PROMOTE READINESS   curl .../strategies/{id}/promote-   ║
║                                                          ║
║  PRE-FLIGHT (new paper run):                             ║
║  □ Control plane green  □ Gates pass  □ Risk limits set ║
║  □ Broker connected     □ No news blackout  □ Paper=30d ║
║                                                          ║
║  START OF DAY:                                           ║
║  □ No stale runs  □ No gaps  □ No DD breaches           ║
║  □ Reconciliation clear  □ Kill switches intentional    ║
║                                                          ║
║  INCIDENT—S1 (<5min):                                    ║
║  1. Contain (kill switch)  2. Investigate  3. Post-mortem║
║                                                          ║
║  KILL SWITCH: curl -X POST .../strategies/{id}/kill \    ║
║    -d '{"actor":"operator","reason":"..."}'              ║
║                                                          ║
║  PROOF OF HEALTH: mvn test -pl trading-examples -Dtest= ║
║    GoldenBacktestTest                                     ║
║                                                          ║
╚══════════════════════════════════════════════════════════╝
```
