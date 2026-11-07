# Hermes Agent — Post-Pull `master` Migration Instructions (2026-11-07)

> **For Hermes Agent (`hermes-gateway.service` on `vps-lin4x.com` / `108.181.191.70`):**
> Execute this runbook immediately after running `git pull origin master` in `/home/administrator/projects/trading-bridge`.

---

## Summary of Changes on `master`

1. **Strategy Catalog Purge (`601 -> 46` Validated Strategies)**:
   - Deleted **101** built-in `.java` strategies and **457** generated/SQ-imported rows in `data/runtime/generator/strategies.db` that failed `RealCostModel` + `SwapCalculator` + `2022–2026` Out-of-Sample Walk-Forward validation.
   - Kept only **12** built-in Java strategies + **34** promoted SQLite strategies (`promoted = 1`).
2. **4-Container Broker Topology (`docker/docker-compose.oanda.yml` & `docker/docker-compose.ibkr.yml`)**:
   - **OANDA (`docker/docker-compose.oanda.yml`)**:
     - `oanda-paper` (`OANDA_ENV=practice` $\rightarrow$ `https://api-fxpractice.oanda.com`, state in `/app/data/state/oanda-paper`, runs `londonorb mondayrev monthweekphase ltdoublema ltcrossmom` on their pinned pairs `EUR_USD`, `GBP_USD`, `USD_CHF`).
     - `oanda-live` (`--profile live`, `OANDA_ENV=live` $\rightarrow$ `https://api-fxtrade.oanda.com`, state in `/app/data/state/oanda-live`).
   - **IBKR (`docker/docker-compose.ibkr.yml`)**:
     - `ib-gateway-paper` (`127.0.0.1:4002->4004/tcp`, `TRADING_MODE=paper`) + `ibkr-paper` (`IBKR_ENV=paper`, port `4002`, `clientId=11/12`, multi-symbol `FUTURES_SYMBOL=MGC,MNQ,MES`, state in `/app/data/state/ibkr-paper`).
     - `ib-gateway-live` (`127.0.0.1:4001->4003/tcp`, `TRADING_MODE=live`, `--profile live`) + `ibkr-live` (`IBKR_ENV=live`, port `4001`, `clientId=21/22`, multi-symbol `FUTURES_SYMBOL=MGC,MNQ,MES`, state in `/app/data/state/ibkr-live`, `--profile live`).
3. **Unified `data/` Directory & Historical Bar Cascade**:
   - All containers mount `./data:/app/data` (legacy `data-futures/` is retired).
   - `IbkrLiveRunner` writes fills to `/app/data/fills/*-paper.jsonl` (or `*-live.jsonl`) and routes historical bar warm-up through `BarSourceResolver` (`IBKR -> Yahoo Finance -> Dukascopy`).

---

## Step-by-Step Execution Checklist for Hermes

### Step 1: Pull `master` & Purge Untracked Legacy Databank JSONs (CRITICAL)

Before restarting `trading-control-plane.service`, delete any untracked `databank.json` or `promoted.json` files in `data/runtime/generator/`. If left on disk, `StrategyGeneratorService.loadPersistedState()` will re-import the 457 deleted strategies back into `strategies.db`.

```bash
cd /home/administrator/projects/trading-bridge
git pull origin master

# CRITICAL: Remove untracked legacy JSON backups and WAL files so strategies.db stays at 34 rows
rm -f data/runtime/generator/databank.json \
      data/runtime/generator/promoted.json \
      data/runtime/generator/strategies.db-shm \
      data/runtime/generator/strategies.db-wal

# Verify exactly 34 promoted strategies remain in SQLite
sqlite3 data/runtime/generator/strategies.db "SELECT COUNT(*), SUM(promoted) FROM generated_strategies;"
# Expected output: 34|34
```

### Step 2: Create Unified State Directories & Migrate Existing Futures Fills/Cursors

```bash
mkdir -p data/state/oanda-paper \
         data/state/oanda-live \
         data/state/ibkr-paper \
         data/state/ibkr-live \
         data/fills

# Migrate existing futures fills and cursors from legacy data-futures/ if present
cp -n data-futures/fills/*.jsonl data/fills/ 2>/dev/null || true
cp -n data-futures/state/*.json data/state/ibkr-paper/ 2>/dev/null || true
cp -n data/state/dev/*.json data/state/oanda-paper/ 2>/dev/null || true
```

### Step 3: Stop & Remove Old Container Names (`trading-dev`, `trading-live`, `trading-futures`)

The legacy container names (`trading-dev`, `trading-live`, `trading-futures`) ran strategies that were deleted in the audit (`vwpreversion`, `consecbar`, `ltrsi3`, `compmomentum`, `sq-gbpjpy-h1-breakout-*`). Stop and remove them:

```bash
docker stop trading-dev trading-live trading-futures 2>/dev/null || true
docker rm trading-dev trading-live trading-futures 2>/dev/null || true
```

### Step 4: Rebuild & Restart `trading-control-plane.service` (`:8080`)

Rebuild the shaded runtime JAR and restart the control-plane service so `/api/strategies` serves only the 46 validated strategies and `LaneTelemetryCollector` monitors the new state paths:

```bash
./mvnw clean package -DskipTests -pl trading-runtime -am
systemctl --user restart trading-control-plane.service
systemctl --user status trading-control-plane.service --no-pager
```

### Step 5: Build & Start `oanda-paper` and `ibkr-paper`

1. **Ensure `.env.paper` exists** in `/home/administrator/projects/trading-bridge/.env.paper` with:
   ```env
   OANDA_API_KEY=<your-oanda-practice-api-key>
   OANDA_ACCOUNT_ID=101-002-4729622-012
   HERMES_WEB_URL=https://hermes.martinfournier.com
   HERMES_WEB_TOKEN=<your-hermes-web-token>
   ```
2. **Launch `oanda-paper`**:
   ```bash
   docker compose -f docker/docker-compose.oanda.yml --project-name tb-oanda up -d --build oanda-paper
   ```
3. **Launch `ibkr-paper`** ( trades `MGC,MNQ,MES` on `127.0.0.1:4002`, `clientId=11/12`):
   - If the existing `ib-gateway` container is already running on `:4002`, start `ibkr-paper` with `--no-deps`:
     ```bash
     docker compose -f docker/docker-compose.ibkr.yml --project-name tb-ibkr up -d --build --no-deps ibkr-paper
     ```
   - Or to run the managed `ib-gateway-paper` + `ibkr-paper` stack together:
     ```bash
     docker compose -f docker/docker-compose.ibkr.yml --project-name tb-ibkr up -d --build
     ```

### Step 6: Post-Deploy Verification

```bash
# 1. Verify active containers
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"

# 2. Verify ibkr-paper multi-symbol initialization (MGC, MNQ, MES)
docker logs --tail 30 ibkr-paper

# 3. Verify oanda-paper initialization (OANDA_ENV=practice, 5 strategies)
docker logs --tail 30 oanda-paper

# 4. Verify Control Plane strategy count (~46 trading strategies + 10 harness probes = 56 total)
curl -s -H "X-API-Key: $(jq -r .serverApiKey data/runtime/remote-core.json 2>/dev/null)" \
  http://localhost:8080/api/strategies | jq '.strategies | length'
```
