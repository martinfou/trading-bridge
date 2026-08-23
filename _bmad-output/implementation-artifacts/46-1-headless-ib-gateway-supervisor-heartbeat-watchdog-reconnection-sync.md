# Story 46.1: Headless IB Gateway Supervisor, Heartbeat Watchdog & Reconnection Sync

Status: ready-for-dev

## Story

As a quantitative systems operator,
I want an IBC-supervised headless Docker container running IB Gateway and an active Java heartbeat watchdog with automated post-reconnect state reconciliation,
So that the automated trading daemon survives mandatory daily 23:45 UTC IBKR gateway reboots, network glitches, and socket drops without desynchronizing open positions or leaving orphaned orders.

## Acceptance Criteria

1. **Given** a Dockerized environment running `ib-gateway` with IBC (IB Controller):
   - The gateway initializes headlessly with auto-login and port bindings (`4001` live / `4002` paper).
   - The scheduled daily reboot at 23:45 UTC is intercepted gracefully during the CME maintenance halt window.
2. **When** the TWS socket connection is severed or drops:
   - `IbkrGatewaySupervisor.java` detects the outage within 30 seconds via active heartbeat ping (`reqCurrentTime()`).
   - The supervisor initiates automatic reconnection with exponential backoff (5s, 10s, 30s, 60s max).
3. **When** the connection to IB Gateway is successfully re-established:
   - `IbkrStateReconciler.java` immediately requests all open positions (`reqPositions()`) and active orders (`reqOpenOrders()`).
   - The retrieved broker state is cross-checked against the local SQLite `trading.db` ledger.
   - Any discrepancies (e.g. fills that occurred while disconnected) are atomically updated in the local database.
4. **And** integration test `IbkrGatewaySupervisorTest.java` passes, validating simulated disconnect, exponential backoff, and state resynchronization.

## Tasks / Subtasks

- [ ] **Task 1: Docker Headless Gateway Configuration (`docker/`)** (AC: 1)
  - [ ] Create `docker/Dockerfile.ibgateway` with IBC automation script and healthcheck.
  - [ ] Configure `docker-compose.ibkr.yml` mounting credentials securely via `.env`.
- [ ] **Task 2: Implement Heartbeat Watchdog (`trading-broker`)** (AC: 2)
  - [ ] Develop `IbkrGatewaySupervisor.java` implementing `EWrapper` connection lifecycle callbacks (`connectionClosed`, `connectAck`).
  - [ ] Add 30-second scheduled timer issuing `reqCurrentTime()` and detecting response timeouts.
  - [ ] Implement exponential backoff retry loop for `eConnect()`.
- [ ] **Task 3: Post-Reconnect State Reconciliation (`trading-broker`, `trading-data`)** (AC: 3)
  - [ ] Develop `IbkrStateReconciler.java` to handle `position()` and `orderStatus()` streams.
  - [ ] Compare incoming broker tickets with SQLite `runs` and `trades` tables.
  - [ ] Rebuild in-memory position state if discrepancies exist.
- [ ] **Task 4: Unit & Mock Integration Tests (`trading-broker`)** (AC: 4)
  - [ ] Author `IbkrGatewaySupervisorTest.java` with mock socket disconnect / reconnect cycles.
