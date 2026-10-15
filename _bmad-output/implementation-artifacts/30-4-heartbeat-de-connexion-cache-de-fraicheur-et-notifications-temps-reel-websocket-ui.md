# Story 30.4: Heartbeat de connexion, sélecteur multi-actifs et notifications temps réel (WebSocket & UI)

Status: backlog

## Story

As a trader,
I want to select my trading asset class (Forex, Futures Small/Mid/Large cap, Equities) on the Live Room dashboard and receive real-time connection status and margin alerts,
So that I never make trading decisions based on stale data or disconnected brokers.

## Acceptance Criteria

1. **Given** `LiveRoom.vue` and `BacktestForm.vue` on Desktop GUI:
   - A multi-asset category selector lets the trader filter or choose:
     - 🏷️ **Forex** (`EUR_USD`, `USD_JPY`...)
     - ⚡ **Futures** (`MES` S&P, `M2K` Russell Small Cap, `EMD` S&P MidCap, `MNQ` Nasdaq)
     - 📈 **Equities** (`IWM`, `MDY`, custom US stock ticker)
   - The UI displays the selected instrument's point multiplier, tick size, and required margin dynamically.
2. **When** no EWrapper callback is received for more than **10 seconds**:
   - The background watchdog triggers a liveness probe (`EClientSocket.reqCurrentTime()`).
3. **When** the probe fails or no response is received within **5 seconds** (total 15 seconds of silence):
   - The broker status transitions to `DISCONNECTED`.
   - The backend immediately broadcasts a `BrokerConnectionStateEvent` via WebSocket to the desktop UI.
4. **When** the connection recovers:
   - The broker transitions to `CONNECTED` and immediately triggers `fetchOpenPositions()` and `fetchAccountSummary()` to resynchronize UI state.
5. **Given** the Vue 3 Live Room dashboard header (`LiveRoom.vue`):
   - 🟢 `CONNECTED` (latency < 2s): Green badge displayed.
   - 🟠 `STALE` (no update > 5s): Orange badge displayed, margin values grayed out with banner *"Données figées il y a X secondes"*.
   - 🔴 `DISCONNECTED`: Red badge displayed, margin cards disabled, and **manual order buttons locked/disabled**.
6. **And** JUnit 5 tests verify state transitions deterministically with a mock clock.

## Tasks / Subtasks

- [ ] **Task 1: Watchdog de heartbeat et liveness probe (`trading-broker`)** (AC: 2, 3, 4)
  - [ ] Développer `IbkrHeartbeatWatchdog` avec sonde `reqCurrentTime()` à 10s et déconnexion à 15s.
  - [ ] Déclencher le resync automatique (`fetchOpenPositions`, `fetchAccountSummary`) lors de la reconnexion.
- [ ] **Task 2: Événements et flux WebSocket (`trading-runtime`)** (AC: 3)
  - [ ] Diffuser les événements `BrokerConnectionStateEvent` sur le bus WebSocket.
- [ ] **Task 3: Sélecteur Multi-Actifs et Dashboard Vue 3 (`desktop/`)** (AC: 1, 5)
  - [ ] Intégrer le sélecteur d'actifs (Forex / Futures / Actions) avec affichage dynamique des multiplicateurs et marges.
  - [ ] Indicateur trichrome (Vert / Orange / Rouge) et verrouillage des boutons d'ordre en cas de déconnexion.
- [ ] **Task 4: Tests unitaires** (AC: 6)
  - [ ] Écrire `IbkrHeartbeatWatchdogTest` avec horloge simulée.
