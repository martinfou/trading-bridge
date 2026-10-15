# Story 30.2: Interception des Fills et Réconciliation double barrière des commissions (Multi-Actifs)

Status: backlog

## Story

As a portfolio manager,
I want to capture execution details (fills) and correlate them with exact commission reports for both Futures and Equities using a bidirectional matching barrier,
So that multi-asset trading costs (flat contract fees and per-share equity commissions) are precisely logged.

## Acceptance Criteria

1. **Given** a market order submitted via `IbkrBroker` (Futures or Equities).
2. **When** the TWS callback `execDetails` is received with an `execId`:
   - If a `commissionReport` with the matching `execId` is already cached (arrived earlier), the fill is immediately committed with the exact commission.
   - Otherwise, a pending execution slot is opened in `IbkrTransactionRegistry`.
3. **When** a `commissionReport` callback arrives:
   - If the matching `execDetails` is already registered, the commission is applied (e.g. flat 0.87 USD for MES/M2K or tiered per-share fee for Equities) and the reconciled fill is published immediately.
   - Otherwise, the commission report is buffered in an unassociated map with a 500 ms expiration window.
4. **When** the 500 ms double-barrier timeout expires before the corresponding callback arrives:
   - The registry logs a warning, falls back to a default fee (0.0 USD or standard configured rate), and commits the fill without blocking the execution thread.
5. **And** all operations in `IbkrTransactionRegistry` are thread-safe and non-blocking.
6. **And** unit tests verify bidirectional arrival orders (`execDetails` first vs `commissionReport` first) on both Futures and Equities.

## Tasks / Subtasks

- [ ] **Task 1: Registre de réconciliation bidirectionnel (`trading-broker`)** (AC: 2, 3, 4, 5)
  - [ ] Développer `IbkrTransactionRegistry` avec `ConcurrentHashMap`.
  - [ ] Supporter les commissions forfaitaires Futures et par action Equities.
  - [ ] Intégrer le timer de fallback à 500 ms.
- [ ] **Task 2: Événements Broker et persistance (`trading-broker`)** (AC: 1, 2, 3)
  - [ ] Publier `BrokerEvent.fill(order, fillPrice, commission)` avec métadonnées d'actif.
- [ ] **Task 3: Tests unitaires** (AC: 6)
  - [ ] Écrire `IbkrTransactionRegistryTest` testant tous les cas nominaux, inversés et timeouts sur Futures et Actions.
