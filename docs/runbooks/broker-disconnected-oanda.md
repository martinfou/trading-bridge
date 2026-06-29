# Broker Disconnected (OANDA)

**Summary:** OANDA REST API is unreachable or returns 5xx for a `LIVE_OANDA` or `PAPER_OANDA` run.

**Signals:**
- `RECONCILIATION_ALERT` with divergence reason `BROKER_UNREACHABLE`
- `HEARTBEAT` events stop for a run
- Run marked `isStale: true` in `/control/summary`
- Broker REST call throws `IOException` or returns HTTP 503

**Severity:** P1 (single strategy) → P0 (all strategies affected)

**Step‑by‑step:**

```text
1. CONFIRM        ┃ curl http://localhost:8080/api/health
                    ┃ curl https://api-fxpractice.oanda.com/v3/accounts/{id}
                    ┃   → if OANDA health endpoint responds, issue may be local

2. ISOLATE        ┃ Check broker-accounts.json config
                    ┃ Check OANDA_API_TOKEN / OANDA_ACCOUNT_ID env vars are set
                    ┃   → export | grep OANDA

3. NETWORK CHECK  ┃ ping api-fxpractice.oanda.com
                    ┃ curl -v https://api-fxpractice.oanda.com/v3/accounts 2> /dev/null | head -20
                    ┃   → Look for DNS / TLS / timeout errors

4. RESTART RUN    ┃ If network is fine and credentials are valid:
                    ┃ POST /api/strategies/{strategyId}/kill
                    ┃   {"actor":"sre","reason":"broker reconnect - restart run"}
                    ┃ POST /api/strategies/{strategyId}/promote
                    ┃   {"targetMode":"PAPER","executionLabel":"PAPER_OANDA"}
                    ┃   → Or promote to LIVE if already past paper period

5. ESCALATE       ┃ If OANDA API itself is down (check status.oanda.com):
                    ┃ → Switch affected strategies to PAPER_STUB for continuity
                    ┃ → Log incident with OANDA support ticket ID
```

**Expected recovery:** Reconnection within 30 seconds on next bar when API is back. Manual restart if reconnection fails.
