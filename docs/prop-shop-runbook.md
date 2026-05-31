# Prop-shop runbook — paper → promote → LIVE

Operational ritual for the 30-day OANDA paper observation period (Story 15.8 / PS-GR16).

> **PAPER_STUB is dev-only.** It never counts toward the LIVE promote gate. Only **PAPER_OANDA** satisfies the paper observation period.

---

## Pipeline overview

```
BACKTEST → promote PAPER_OANDA → 30-day observation → promote LIVE → kill switch if needed
```

| Stage | Execution label | Counts toward paper period? |
|-------|-----------------|------------------------------|
| Backtest | `BACKTEST` | — |
| Dev stub | `PAPER_STUB` | **No** — local replay only |
| OANDA demo | `PAPER_OANDA` | **Yes** — required before LIVE |
| Live broker | `LIVE_OANDA` | — |

---

## Daily review checklist

Run this once per day while a strategy is on **PAPER_OANDA**:

1. **Control room** — `GET /control/summary` (or `/api/control/summary`)
   - Any run `isStale`?
   - Any `signals.gaps[]`?
   - `dailyDdBreached` on broker runs?

2. **Promote readiness** — `GET /api/strategies/{strategyId}/promote-readiness`
   - `paperElapsedDays` vs `paperDaysRequired` (default 30)
   - All `gates[]` with `passed: true`?
   - `reconciliation.clear` is `true`?
   - `killSwitchActive` is `false`?

3. **Reconciliation** — review `RECONCILIATION_ALERT` events in evidence export if `reconciliation.alertCount > 0`

4. **Risk** — confirm daily drawdown and pre-trade limits in `data/runtime/risk-limits.json`

5. **Decision log** — note promote / hold / kill rationale (manual journal; not automated)

---

## API reference

### Promote readiness

```bash
curl http://localhost:8080/api/strategies/LondonOpenRangeBreakout/promote-readiness
```

Response (`schemaVersion: 1`):

| Field | Meaning |
|-------|---------|
| `targetMode` | Next promote step (`PAPER` if undeployed, `LIVE` if on paper) |
| `ready` | All gates pass for `targetMode` |
| `gates[]` | Named checks with `passed`, `message`, optional `threshold` / `actual` |
| `paperElapsedDays` | Days on PAPER_OANDA (LIVE target only) |
| `paperDaysRequired` | Configured minimum (default 30) |
| `reconciliation` | `alertCount`, `affectedRunIds`, `clear` |
| `killSwitchActive` | Strategy-level kill flag |

### Promote

```bash
# Backtest → PAPER_OANDA (requires OANDA credentials)
curl -X POST http://localhost:8080/api/strategies/LondonOpenRangeBreakout/promote \
  -H 'Content-Type: application/json' \
  -d '{"targetMode":"PAPER","runId":"<backtest-run-id>","executionLabel":"PAPER_OANDA","brokerAccountId":"default"}'

# PAPER_OANDA → LIVE (after 30 days + gates)
curl -X POST http://localhost:8080/api/strategies/LondonOpenRangeBreakout/promote \
  -H 'Content-Type: application/json' \
  -d '{"targetMode":"LIVE"}'
```

HTTP 422 when gates fail — inspect `checks[]` in response body.

### Kill switch

```bash
curl -X POST http://localhost:8080/api/strategies/LondonOpenRangeBreakout/kill \
  -H 'Content-Type: application/json' \
  -d '{"actor":"martin","reason":"manual halt"}'
```

---

## Promote decision matrix

| Condition | Action |
|-----------|--------|
| `ready: false`, paper days remaining | **Hold** — continue observation |
| `reconciliation.clear: false` | **Hold** — investigate divergences before LIVE |
| `killSwitchActive: true` | **Hold** — resolve kill before new runs |
| `dailyDdBreached: true` on summary | **Hold** — review risk limits |
| All gates pass, 30+ days, reconciliation clear | **Promote to LIVE** (deliberate POST) |
| Strategy behaviour unacceptable | **Kill** + archive deployment |

---

## Configuration files

| File | Purpose |
|------|---------|
| `data/runtime/promote-gates.json` | `paperDaysBeforeLive`, metric thresholds |
| `data/runtime/risk-limits.json` | Pre-trade and daily drawdown limits |
| `data/runtime/broker-accounts.json` | Multi-account prop routing (Story 16.9) |
| OANDA credentials | Environment / local config (never commit) |

---

## Evidence & due diligence

- JSONL export: `GET /api/runs/{runId}/export`
- HTML report: `GET /api/runs/{runId}/export?format=html`

See `docs/testing.md` for test commands and endpoint details.
