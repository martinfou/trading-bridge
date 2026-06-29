# Operator Dashboard Guide

**Purpose** – Provide SREs, Operators, and developers with a quick‑reference for using the web‑based **Operator Dashboard** (the Vue 3/Electron UI) to monitor and manage Trading Bridge runs.

---

## 1. Accessing the Dashboard

| Platform | URL / Path |
|----------|------------|
| **Desktop (Electron)** | Launch `npm run electron:dev` from `desktop/` (requires the fat JAR for the control plane). |
| **Web (Laravel)** | `http://<host>:8080/dashboard` – served by the built‑in Express static server (enabled when `NODE_ENV=production`). |

The dashboard automatically authenticates to the local control‑plane (port 8080). No additional login is required on a developer machine.

---

## 2. Main UI Sections

1. **Live Run Monitor** – Table of all active runs, colour‑coded by status (green = RUNNING, orange = STALE, red = ERROR). Columns include:
   * `Run ID`
   * `Strategy`
   * `Execution Label`
   * `Bars`
   * `Stale`
   * `Daily DD %`
   * `Drift`
2. **Alerts Panel** – Real‑time list of critical alerts (P0/P1). Clicking an alert jumps to the related run.
3. **Promote Gate View** – Shows the result of `GET /api/strategies/{id}/promote-readiness` with pass/fail icons.
4. **Event Stream** – Live WebSocket view of `ORDER`, `FILL`, `RECONCILIATION_ALERT`, and `HEARTBEAT` events for the selected run.
5. **Risk Gauges** – Horizontal bars displaying current `maxDailyDrawdownPct` utilisation and remaining SQLite backup window.

---

## 3. Typical Operator Workflow

| Step | Action | UI Location |
|------|--------|--------------|
| **A. Detect Issue** | Monitor the **Alerts Panel** for P0/P1 alerts (e.g., `Broker Disconnected`). | Alerts Panel |
| **B. Inspect Run** | Click the run row → opens **Event Stream** and **Run Details**. | Live Run Monitor |
| **C. Kill Run** | Press **Kill** button (red ⚔️) – confirms with a modal. | Live Run Monitor (row actions) |
| **D. Promote** | Open **Promote Gate View**, verify all gates pass, then click **Promote**. | Promote Gate View |
| **E. Verify** | Watch the first 5 min in **Event Stream** for `ORDER_SUBMITTED` → `FILL`. | Event Stream |
| **F. Log Incident** | Click **Log** → fills a pre‑populated template in `docs/incident-log.md`. | Alerts Panel (Log button) |

---

## 4. Keyboard Shortcuts (Desktop)

| Shortcut | Function |
|----------|----------|
| `Ctrl+F` / `⌘+F` | Global search for strategy IDs. |
| `Alt+K` | Trigger **Kill** on the currently selected run. |
| `Alt+P` | Open **Promote** dialog for the selected run. |
| `Shift+L` | Open **Log Incident** modal. |
| `Esc` | Close any modal. |

---

## 5. Troubleshooting Tips

* **Dashboard fails to load** – Ensure the control plane is running (`GET /api/health` = 200). Restart Electron (`npm run electron:dev`).
* **Stale data** – Verify the WebSocket connection (`ws://localhost:8080/ws/runs/{runId}`). Check browser dev‑tools network tab for errors.
* **Missing alerts** – Confirm the SRE alerting service (Discord/Telegram webhook) is configured in `config/alerting.yaml`. Restart the alerting daemon if needed.
* **Performance lag** – Reduce concurrent runs to ≤ 3 per broker (see `docs/incident-severity-matrix.md`).

---

## 6. Export & Reporting

* **Export run data** – Click the **Export** button on a run row to download a JSONL of all events.
* **Generate PDF report** – Use the **Report** dropdown → **PDF Summary** (requires `wkhtmltopdf` installed).

---

*Prepared by the Platform Reliability team – Winston (Architect).*
