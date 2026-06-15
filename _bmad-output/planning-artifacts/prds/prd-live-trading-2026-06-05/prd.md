---
title: Live & Paper Trading Dashboard (Live Room)
status: final
created: 2026-06-05
updated: 2026-06-15
author: John (Product Manager)
---

# Live & Paper Trading Dashboard (Live Room)

## 1. Vision & Goals
Provide a world-class, institutional-grade ("prop-shop quality") control center for monitoring active paper-trading and live-broker execution runs in real-time. The dashboard will mirror the visual richness of the backtest analysis suite but adapt to live environments: exposing active strategy heartbeats, real-time risk indicators, open positions, completed transaction histories, and terminal deactivation actions.

## 2. Personas & Roles
- **Operator (Prop-Shop Trader / Platform Admin):** Single user responsible for deactivating, monitoring, and terminating automated runs. Requires immediate visual feedback on strategy status, quick access to risk metrics (drawdowns), and a reliable, instantaneous mechanism to halt trading (Kill Switch).

## 3. User Journey (Live Room Session)
1. **Status Scan:** Operator opens the "Live Room" from the sidebar navigation.
2. **Health Check:** Operator inspects top-level KPIs (Total Running Strategies, Overall Daily PnL, Alerts/Stale indicators) and scans the active strategy cards for warnings.
3. **Drill Down:** Operator clicks "Inspect" on an active strategy card (e.g., `OverlapMomentumBurst` on `GBP_USD` running in `LIVE` mode).
4. **Detail Analysis:** A high-fidelity inspect drawer/view loads, showing the real-time candlestick price chart (mapped with entry/exit indicators), current open floating positions, and the scrollable log of completed trades.
5. **Emergency Stop:** Finding a metric threshold breached, the Operator clicks the prominent **Kill Switch** on the inspect panel to cleanly deactivate the strategy.

## 4. Functional Requirements

### Sidebar Navigation & Entry
- **FR-1.1 (Sidebar Option):** The main sidebar navigation must contain a new "Live Room" item utilizing a telemetry/signal icon (📡 or ⚡) linking to `/live-trading`.
- **FR-1.2 (Active State):** When active, the "Live Room" icon and label must highlight in the accent color (amber/gold) to match the platform's styling system.

### Live Room Overview Dashboard
- **FR-2.1 (Top-Level KPI Strip):** A summary row must display real-time statistics aggregated from all active runs:
  - Total Active Live Strategies
  - Total Active Paper Strategies
  - Net Capital Allocated
  - Combined Realized PnL (today)
  - Active Alerts (Count of stale runs, database gaps, or active drift signals)
- **FR-2.2 (Active Strategy Cards):** A responsive grid displaying individual cards for each strategy currently executing in `LIVE` or `PAPER` mode.
- **FR-2.3 (Card Details):** Each strategy card must display:
  - Strategy ID and Asset/Symbol
  - Execution Badge: Color-coded to clearly distinguish `LIVE` (green border/text) vs `PAPER` (blue border/text) modes.
  - Runtime Status: Displays `RUNNING`, `PAUSED` (due to drawdown limits), or `STALE` (no heartbeat received within the threshold).
  - Telemetry Details: Running duration, event count, and latest event timestamp.
  - Risk Metrics: Live daily drawdown % and max daily drawdown % with a warning indicator if close to limits.
- **FR-2.4 (Action Triggers):** Each card must feature a prominent **Inspect** button and a secondary **Kill Switch** button.
- **FR-2.9 (Drift Status Badge):** If a running strategy has a non-`HOLD` drift evaluation recommendation (e.g. `SUSPEND` or `RETUNE`), the strategy card must display a highly visible, color-coded badge indicating the drift recommendation (e.g., orange for retune, red for suspend).

### Advanced Risk Controls (Circuit Breakers & Locks)
- **FR-2.5 (Daily Loss Limit - DLL):** Hard stop at a configurable percentage (e.g., default 5.0% for prop-shops or 2.0% conservative) of starting daily equity. Upon breach, the system must immediately close all open positions, cancel all active orders, and transition the strategy status to `SUSPENDED_DAILY` (lockout active until next calendar day's 5:00 PM EST/UTC rollover).
- **FR-2.6 (Weekly Loss Limit - WLL):** Cumulative stop at a configurable percentage (e.g., default 10.0%) of starting weekly equity (Monday open). Upon breach, all strategy operations are halted, positions are flatted, and status transitions to `SUSPENDED_WEEKLY` until the weekly session resets.
- **FR-2.7 (Strategy Volatility Lock / Cooldown):** A temporary cooling-off lockout (e.g., 4 hours) triggered automatically if a strategy registers 3 consecutive losses or 5 total losses within a rolling 2-hour window. Status transitions to `COOLDOWN`.
- **FR-2.8 (Lockout Status Display):** Strategy cards and detail panels in the Live Room must display the active suspension reason (`SUSPENDED_DAILY`, `SUSPENDED_WEEKLY`, or `COOLDOWN`) and the remaining lockout time.

### Inspection View (Slide-out Drawer or Nested View)
- **FR-3.1 (Inspection Layout):** Inspecting a running strategy must load a tabbed results workspace:
  - **Overview Tab:** General strategy configuration, allocated capital, and daily drawdown metrics.
  - **Open Positions Tab:** Real-time list of currently open positions, detailing:
    - Entry time, Side (BUY/SELL), Quantity, Entry Price, Current Price, and Floating P&L.
  - **Trades Tab:** Scrollable table of completed trades (derived dynamically from fill events) containing Entry/Exit timestamps, exit price, and realized P&L.
  - **Price Chart Tab:** Lightweight-charts candlestick visualization showing OHLC price bars and mapping trade execution markers (Entry/Exit arrows and labels) on the corresponding bar timestamps.
  - **Drift Analysis Tab:** Displays active model drift metrics, including:
    - Overall recommendation state (e.g., HOLD, SUSPEND, RETUNE), source of comparison data, and evaluation timestamp.
    - An explanatory reason summary for the drift.
    - A detailed metrics table: Metric Name, Observed Value, Allowed Threshold, Dimension (e.g., Drawdown, Win Rate), and Breached status (true/false).
- **FR-3.2 (Administrative Kill Switch):** A critical administrative panel featuring a highly visible **Kill Strategy** trigger. Clicking this de-registers and cleanly stops order submission for the selected strategy.
- **FR-3.3 (TUI Status Drift Section):** The `/status` command in the terminal client must include a dedicated **"Drift Alerts"** section when active runs are present. This section lists any running strategies whose drift recommendation is not `HOLD`, showing the strategy ID, recommendation, and reason.

## 5. Non-Functional Requirements & Performance
- **NFR-4.1 (Low Latency Updates):** The active runs list and status stats must refresh automatically every 10 seconds or utilize the WebSocket event channel for real-time telemetry updates.
- **NFR-4.2 (Safety Lock):** Triggering the **Kill Switch** must present a confirmation dialog to prevent accidental deactivations during active trading sessions.
- **NFR-4.3 (Fault Tolerant Recovery):** The UI must handle network drops gracefully by displaying connection loss alerts without freezing the user interface thread.

## 6. Exclusions & Out of Scope
- Direct manual order placement (adjusting prices, setting stop-loss levels manually from the UI) is out of scope for the initial release. Deactivation is limited to de-registering/killing the running strategy.
