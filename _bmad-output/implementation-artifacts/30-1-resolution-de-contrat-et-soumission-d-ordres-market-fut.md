# Story 30.1: Résolution de contrat et soumission d'ordres Multi-Actifs (Futures CME & Actions US)

Status: backlog

## Story

As a trader,
I want to submit MARKET orders for Futures contracts (MES, M2K, EMD) and US Equities (STK) to IBKR,
So that my multi-asset trading signals are executed immediately in live/paper markets.

## Acceptance Criteria

1. **Given** configurable connection settings supporting standard TWS & IB Gateway ports:
   - Live TWS: `7496` | Paper TWS: `7497`
   - Live Gateway: `4001` | Paper Gateway: `4002`
2. **When** `IbkrBroker` connects, it initializes the next valid order ID via the `nextValidId` callback asynchronously.
3. **When** an order request is received:
   - **For Futures (`MES`, `M2K`, `EMD`, `MNQ`)**: `IbkrContractResolver` creates a `Contract` with `SecType.FUT`, exchange `CME`, currency `USD`, and the active contract month.
   - **For Equities (`IWM`, `MDY`, `AAPL`...)**: `IbkrContractResolver` creates a `Contract` with `SecType.STK`, exchange `SMART`, and currency `USD`.
4. **When** a MARKET order (`OrderType.MKT`) is submitted via `EClientSocket.placeOrder`:
   - `IbkrBroker` transitions the order state to `SUBMITTED` internally and returns `OrderSubmitResult.filled(...)` or `submitted` without blocking the caller thread.
5. **And** all Forex/OANDA components remain strictly untouched (0.0 delta).

## Tasks / Subtasks

- [ ] **Task 1: Résolution de contrat Multi-Actifs (`trading-broker`)** (AC: 1, 3)
  - [ ] Implémenter `IbkrContractResolver` supportant `SecType.FUT` (CME) et `SecType.STK` (SMART, USD).
- [ ] **Task 2: Soumission d'ordres et synchronisation d'ID (`trading-broker`)** (AC: 2, 4)
  - [ ] Gérer l'incrémentation atomique de `nextOrderId` initialisé par `nextValidId`.
  - [ ] Implémenter `submitMarketOrder` unifié pour Futures et Actions.
- [ ] **Task 3: Mock TCP Gateway & Tests (`trading-broker`)** (AC: 2, 4, 5)
  - [ ] Étendre `MockTcpGatewayServer` pour simuler `FUT` et `STK`.
  - [ ] Écrire `IbkrMultiAssetOrderSubmissionTest`.
