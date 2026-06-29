# Daily / Weekly Review Process

The SRE team runs a structured review of the platform health on a regular cadence.

## Daily Review (≈ 30 min)

1. **Metrics snapshot** – Pull the latest monitoring dash (`GET /control/summary`). Verify:
   - All brokers report `connected: true`.
   - No `P0` incidents in the Incident Severity Matrix.
   - Error‑budget consumption < 50 % for all SLOs.
2. **Log check** – Scan `trading-runtime.log` for `ERROR` or `WARN` entries.
3. **Backup validation** – Ensure yesterday's SQLite backup exists and `PRAGMA integrity_check;` passes.
4. **Run promotion gate status** – Verify the golden backtest CI is green.
5. **Action items** – Record any anomalies in the weekly sprint board.

## Weekly Review (≈ 60 min)

1. **Incident post‑mortem** – Review any incidents logged in the past week, update the Incident Severity Matrix if classification changed.
2. **SLO health** – Plot weekly error‑budget consumption; discuss any trends.
3. **Capacity planning** – Check broker rate‑limit usage, DB size growth, and plan scaling if needed.
4. **Runbook audit** – Verify steps in all runbooks are still accurate; update as required.
5. **Stakeholder sync** – Send a summary report to product owners and architects.

**Artifacts** – All findings are recorded in `docs/weekly-review-report.md` (generated automatically).
