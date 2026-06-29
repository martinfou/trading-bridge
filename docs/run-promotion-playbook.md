# Run Promotion Playbook

**Purpose** – Provide a step‑by‑step, repeatable process for promoting a strategy from *Paper* to *Live* (or between any execution labels) while satisfying all SRE gate checks.

---

## 1. Prerequisites

| Item | Requirement |
|------|-------------|
| **Strategy** | Must have successfully completed the *Paper* phase (≥ 30 calendar days) and passed all automated promote‑gate checks. |
| **Risk Limits** | `maxDailyDrawdownPct` ≤ 5 % and no active `dailyDdBreached` flag in the last 30 days. |
| **Reconciliation** | No pending `RECONCILIATION_ALERT` events (`promote‑readiness` → `reconciliation.clear == true`). |
| **Operator Knowledge** | Familiar with the **Operator Dashboard** (see *Operator Dashboard Guide*). |
| **Backup** | Recent SQLite backup (events & deployments) – verified via `sqlite3 data/runtime/events.db "PRAGMA integrity_check;"`. |

---

## 2. Run‑Gate Validation (Automated)

```bash
curl -s http://localhost:8080/api/strategies/{strategyId}/promote-readiness \
  | jq '.gates[] | select(.passed==false)'
```

If any gate fails, address the root cause before proceeding (see *Runbooks → Promote Gate Failure*). Typical failures:

* **min_trades** – strategy generated zero trades; investigate signal logic.
* **max_drawdown_pct** – risk limits exceeded; tune `risk-limits.json`.
* **golden_baseline** – metrics drift > 1 %; run `GoldenBaselineCapture` and update baseline if intentional.
* **paper_duration_days** – required 30 days not met; wait.
* **oos_holdout** / **execution_stress** – adjust thresholds or disable for low‑frequency strategies.

---

## 3. Manual Review Checklist (SRE)

| # | Check | Owner | Status |
|---|-------|-------|--------|
| 1 | Verify *Paper* run completed without `dailyDdBreached`. | SRE | ✅ |
| 2 | Confirm no open *Stale* runs for the strategy. | SRE | ✅ |
| 3 | Review latest **Operator Dashboard** alerts for the strategy. | Operator | ✅ |
| 4 | Ensure OANDA/IBKR credentials are valid (`env` or `broker-accounts.json`). | Ops | ✅ |
| 5 | Validate backup integrity (see *Prerequisites*). | SRE | ✅ |

---

## 4. Promotion Execution

1. **Kill any existing *Live* run** (if present) to avoid duplicate positions:
   ```bash
   curl -X POST http://localhost:8080/api/strategies/${STRATEGY_ID}/kill \
        -H 'Content-Type: application/json' \
        -d '{"actor":"sre","reason":"promotion – replace existing live run"}'
   ```
2. **Promote** to the target execution label (e.g., `LIVE_OANDA`):
   ```bash
   curl -X POST http://localhost:8080/api/strategies/${STRATEGY_ID}/promote \
        -H 'Content-Type: application/json' \
        -d '{"targetMode":"LIVE","executionLabel":"LIVE_OANDA"}'
   ```
3. **Verify** response – HTTP 200 with `status: "promoted"` and a new `runId`.
4. **Monitor** the new run for the first 5 minutes via:
   ```bash
   curl http://localhost:8080/control/summary | jq '.runs[] | select(.runId=="${NEW_RUN_ID}")'
   ```
   Ensure `isStale` is **false** and no `RECONCILIATION_ALERT` appears.

---

## 5. Post‑Promotion Validation (5 min window)

| Metric | Expected | Tool |
|--------|----------|------|
| **Health** | `GET /api/health` = 200 | curl |
| **Run State** | `RUNNING` and `isStale:false` | control summary |
| **Order Flow** | At least one `ORDER_SUBMITTED` → `FILL` within 30 s | event export |
| **Risk** | No `ordersDailyDdBlocked` increment | control summary |

If any condition fails, **rollback**:

```bash
curl -X POST http://localhost:8080/api/strategies/${STRATEGY_ID}/kill \
     -H 'Content-Type: application/json' \
     -d '{"actor":"sre","reason":"promotion rollback – health check failed"}'
```
Then re‑promote to `PAPER` (or keep the previous live version) after fixing the issue.

---

## 6. Documentation & Incident Logging

* Record promotion details in the **Incident Log** (`docs/incident-log.md`): strategy ID, timestamps, gate results, and any manual actions.
* Update the **Run Promotion Playbook** version header (e.g., `v1.2 – 2026‑06‑29`).
* If a new gate failure pattern emerges, create a dedicated runbook (see *Runbooks → Promote Gate Failure*).

---

*Prepared by the Platform Reliability team – Winston (Architect).*
