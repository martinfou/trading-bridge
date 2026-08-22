# Story 30.3: Résumé de compte et Métriques de marge Multi-Actifs (API REST générique & cache)

Status: backlog

## Story

As a risk manager,
I want to view margin requirements, equity, and buying power across Futures and US Equities via generic API endpoints,
So that I can monitor portfolio health from the Live Room.

## Acceptance Criteria

1. **Given** an active connection to TWS / IB Gateway.
2. **When** TWS streams account updates via `accountSummary` and `accountSummaryEnd` callbacks:
   - Values for `NetLiquidation`, `InitMarginReq`, `MaintMarginReq`, `FullAvailableFunds` (Free Margin), `BuyingPower`, `RegTMargin`, and `DayTradesRemaining` are atomically updated in `IbkrAccountCache` with a UTC `Instant` timestamp.
3. **When** a client queries `GET /api/brokers/{brokerId}/account-summary` or `GET /api/portfolio/margins`:
   - The controller returns a normalized `AccountSummaryResponse` JSON DTO:
     ```json
     {
       "brokerId": "ibkr",
       "netLiquidation": 52420.50,
       "initialMarginReq": 2900.00,
       "maintenanceMarginReq": 2355.00,
       "freeMargin": 49520.50,
       "buyingPower": 198082.00,
       "regTMargin": 12500.00,
       "dayTradesRemaining": 3,
       "currency": "USD",
       "updatedAt": "2026-08-22T00:25:00Z"
     }
     ```
4. **And** the data model cleanly decouples IBKR specifics from the Control Plane core, allowing uniform multi-broker dashboarding.

## Tasks / Subtasks

- [ ] **Task 1: Cache mémoire thread-safe `IbkrAccountCache` (`trading-broker`)** (AC: 1, 2)
  - [ ] Stocker NetLiquidation, InitMargin, MaintMargin, FreeMargin, BuyingPower, RegTMargin, DayTradesRemaining.
- [ ] **Task 2: Endpoints REST génériques (`trading-runtime`)** (AC: 3, 4)
  - [ ] Exposer `GET /api/brokers/{brokerId}/account-summary` et `GET /api/portfolio/margins`.
- [ ] **Task 3: Tests unitaires** (AC: 1-4)
  - [ ] Écrire `IbkrAccountSummaryControllerTest`.
