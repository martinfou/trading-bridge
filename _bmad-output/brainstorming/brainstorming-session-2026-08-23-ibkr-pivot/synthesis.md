# Brainstorming Synthesis: Strategic Pivot to IBKR-First CME Micro-Futures Autopilot

> **Date**: August 23, 2026  
> **Session Lead**: Martinfou & AI Creative Partner  
> **Scope**: Backlog Triage, Multi-Asset Architecture & Autonomous Swing Engine on Interactive Brokers

---

## 1. Executive Summary & Strategic Shift

Trading Bridge is executing a definitive strategic pivot: **transitioning from retail Forex/OANDA toward an institutional-grade, fully automated CME Micro-Futures (`MES`, `MNQ`, `M2K`, `MGC`, `MCL`) swing trading platform powered by Interactive Brokers (IBKR)**.

This shift:
- Eliminates the low-latency noise and high spread friction of intraday scalping by targeting **H1 (hourly) and D1 (daily) swing trends**.
- Maximizes capital efficiency on smaller accounts via **CME Micro contracts** and an ultra-conservative **50% max margin shield**.
- Archives obsolete legacy epics (JForex parsing, OANDA reconnection hacks) to focus 100% of engineering bandwidth on the **IBKR Live Autopilot Engine**.

---

## 2. Backlog & Epic Triage Decisions

| Category | Epics | Action | Rationale |
| :--- | :--- | :---: | :--- |
| **Legacy / Obsolete** | **Epic 14** (JForex/SQ XML Import)<br>**Epic 18** (Distributed Cluster Nodes)<br>**Epic 31–33** (OANDA Stream Reconnects) | 🗄️ **ARCHIVE** | JForex and OANDA-specific API streaming quirks are irrelevant for an IBKR futures core. Distributed clustering is premature complexity. |
| **Institutional IP (Retained)** | **Epic 15 / 19** (Promotion Gates & OOS Testing)<br>**Epic 23** (Monte Carlo VaR Engine)<br>**Epic 26** (SQLite Backtest & Trade Ledger)<br>**Epic 42 / 44** (WFA Hub & Taxonomy Selector) | 💎 **KEEP & LEVERAGE** | Critical statistical validation infrastructure ensuring strategies pass rigorous out-of-sample and parameter stability gates before live deployment. |
| **New Core Priority** | **Epic 45** (CME Realism & Microstructure)<br>**Epic 46** (IBKR Live Autopilot & Headless Gateway) | 🚀 **ACTIVE FOCUS** | The foundational execution pipeline running 24/5 on a headless VPS/server connected to Interactive Brokers. |

---

## 3. Target System Architecture: The IBKR Swing Autopilot

```
                     ┌─────────────────────────────────────────────────────────┐
                     │            TRADING BRIDGE DESKTOP / CONTROL PLANE       │
                     │          (Electron + Vue 3: Monitoring & Dashboards)    │
                     └────────────────────────────┬────────────────────────────┘
                                                  │ (REST / WebSocket API)
                                                  ▼
                     ┌─────────────────────────────────────────────────────────┐
                     │               HEADLESS 24/5 TRADING DAEMON             │
                     │           (Dockerized Java Runtime + IB Gateway)        │
                     ├─────────────────────────────────────────────────────────┤
                     │ ⏰ Bar-Close Scheduler (Wakes at :00:05 of H1 / D1 bars) │
                     │ 🧠 Strategy Portfolio Evaluator (Micro-5 Ensemble)     │
                     │ 🛡️ Global Margin Guard (Max 2 pos, max 50% equity cap)  │
                     │ 🔄 Auto-Rollover Service (CME quarterly calendar spread)│
                     │ 📊 SQLite Persistent Trade & Audit Ledger               │
                     └────────────────────────────┬────────────────────────────┘
                                                  │ (Pure Java TWS Socket API)
                                                  ▼
                     ┌─────────────────────────────────────────────────────────┐
                     │            INTERACTIVE BROKERS (IBKR TWS / GATEWAY)     │
                     │              Direct Routing to CME Globex Matcher       │
                     │        • Hard Disaster Stop Loss (Native CME OCA)       │
                     │        • Dynamic Trailing Stops / Breakeven at Close    │
                     └─────────────────────────────────────────────────────────┘
```

### Core Architecture Pillars:
1. **Deployment**: Headless 24/5 Docker container running `ib-gateway` + Java runtime on a dedicated VPS/mini-PC. Electron desktop acts as a remote management UI.
2. **Order Execution & Protection**: **Hybrid Stop Model** — hard disaster stop submitted directly to CME as an IBKR OCA bracket upon entry; trailing stops and breakevens dynamically adjusted at bar closes.
3. **Capital Sizing (Small Account Safe)**:
   - Base sizing: **1 micro contract per signal**.
   - Concurrency limit: **Maximum 2 simultaneous active positions**.
   - Margin ceiling: **Never exceed 50% account margin utilization**.
   - Growth tiers: Unlock 2nd/3rd contracts automatically as equity compounds (+\$5k per tier).
4. **Starting Strategy Lineup (The Micro-5 Ensemble)**:
   - **`MES` / `MNQ`**: Volatility-Contraction Breakouts (`LtBollingerSqueeze` on H1).
   - **`M2K`**: Trend-Filtered Pullback Entries (`LtPullbackEntry` on H1).
   - **`MGC` (Gold)**: Macro Trend-Following (`LtCrossMomentum` on D1).

---

## 4. 4-Stage Phased Implementation Roadmap

* **Stage 1 (Backlog Refactor & Epic 46 Definition)**:
  - Update `sprint-status.yaml` to mark legacy OANDA epics as `superseded`.
  - Author formal specifications for **Epic 46: IBKR Live Autopilot & Headless Gateway Daemon**.
* **Stage 2 (Engine Implementation)**:
  - Implement `IbkrLiveRunner.java` handling socket connections, bar-close event triggers, CME OCA bracket emission, and SQLite trade reconciliation.
  - Implement `CmeAutoRolloverManager.java` for quarterly calendar spread execution.
* **Stage 3 (Validation & IBKR Paper Run)**:
  - Deploy daemon to IBKR Paper Trading Gateway (demo account, port `7497`/`4002`).
  - Run the 3-strategy ensemble for 2–4 weeks to audit execution parity and ensure zero drift.
* **Stage 4 (Live Capital Deployment)**:
  - Transition to live funded IBKR account with 1-micro sizing and strict 50% margin guard.
