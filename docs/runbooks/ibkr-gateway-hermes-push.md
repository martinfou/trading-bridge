# Runbook: IBKR Gateway Hermes Publisher

## Overview

The `HermesIbkrPublisher` connects to a locally running Interactive Brokers Gateway (or TWS), extracts account metrics, positions, open bracket orders, and closed trade fills, and pushes them to `hermes-web` via authenticated HTTP requests.

---

## Prerequisites
1. **IB Gateway**:
   - Running locally on `127.0.0.1:4002` (paper) or `4001` (live).
   - "Enable ActiveX and Socket Clients" checked.
   - Socket port matching configuration (`4002`).
   - "Read-Only API" **unchecked** (to allow trade and execution extraction).
   - Master API client ID set to `90` (or configured client ID).
2. **hermes-web**:
   - Running locally or deployed at target URL (e.g. `http://127.0.0.1:8000` or production URL).
   - Valid API bearer token (`HERMES_API_TOKEN`).

---

## Configuration

Configuration is loaded from environment variables or `data/runtime/hermes-ibkr-state.json`:

| Environment Variable | Default Value | Description |
|----------------------|---------------|-------------|
| `IBKR_HOST` | `127.0.0.1` | Host where IB Gateway is listening |
| `IBKR_PORT` | `4002` | Port (4002 for paper, 4001 for live) |
| `IBKR_CLIENT_ID` | `90` | API Client ID |
| `HERMES_BASE_URL` | `http://127.0.0.1:8000` | Base URL of hermes-web |
| `HERMES_API_TOKEN` | *(required in prod)* | Bearer token for authentication |

---

## Running the Publisher

### One-Shot Execution (Cron Mode)
Runs a single extraction and push cycle, then shuts down cleanly:
```bash
java -cp target/trading-runtime-1.0.0-SNAPSHOT-shaded.jar \
  com.martinfou.trading.runtime.hermes.HermesIbkrPublisherMain --once
```

### Continuous Daemon Mode
Runs indefinitely, pushing fresh snapshots every 60 seconds (or configured interval):
```bash
java -cp target/trading-runtime-1.0.0-SNAPSHOT-shaded.jar \
  com.martinfou.trading.runtime.hermes.HermesIbkrPublisherMain --interval 60
```

---

## Verification & Troubleshooting

### Checking Snapshot Ingest
Query the latest snapshot from `hermes-web`:
```bash
curl -s http://127.0.0.1:8000/trading/api/broker/ibkr | jq .
```
Expected output:
```json
{
  "connected": true,
  "account_id": "DUR967322",
  "pushed_at": "2026-10-19T04:40:36Z",
  "fresh": true,
  "payload": {
    "account": {
      "net_liquidation": 11282.14,
      "total_cash": 11282.14,
      "currency": "CAD"
    }
  }
}
```

### Common Errors
1. **Connection Refused (127.0.0.1:4002)**:
   - Ensure IB Gateway is running and logged in.
   - Verify that API settings in IB Gateway have socket port `4002` enabled and "Allow connections from localhost only" checked.
2. **Client ID Already In Use**:
   - Ensure no other client or previous process is holding client ID `90`.
3. **HTTP 401 Unauthorized**:
   - Check that `HERMES_API_TOKEN` is set and matches `TRADING_API_TOKEN` in `hermes-web/.env`.
