# Story 16.9: Multi-compte prop — BrokerAccount (prop-shop)

Status: review

## Story

As a Martin,
I want separate broker accounts linked to isolated deployments,
So that multiple prop firm accounts do not share PnL or risk limits (PS-GR15).

## Acceptance Criteria

1. **AC1 — Config:** Multiple accounts via `data/runtime/broker-accounts.json` + per-account env vars.
2. **AC2 — Deployments:** `DeploymentRecord.brokerAccountId` persisted (memory + SQLite).
3. **AC3 — Routing:** Runs and promotes use deployment account; cross-account requests blocked.
4. **AC4 — Events:** RUN_STARTED tags `brokerAccountId`; evidence export includes it.
5. **AC5 — API:** `GET /api/broker-accounts` returns masked views (no secrets).
6. **AC6 — Tests:** `DeploymentStoreTest`, `BrokerAccountRegistryTest`, `BrokerAccountRoutingTest`.

## Tasks / Subtasks

- [x] Task 1: BrokerAccountRegistry + config file (AC: 1, 5)
- [x] Task 2: DeploymentRecord + RunConfigSnapshot brokerAccountId (AC: 2, 3)
- [x] Task 3: BrokerFactory, RunManager, PromoteService wiring (AC: 3, 4)
- [x] Task 4: API + SQLite migration (AC: 5)
- [x] Task 5: Tests + docs
- [x] Task 6: Verify build

## Dev Agent Record

### File List

- `trading-runtime/src/main/java/com/martinfou/trading/runtime/BrokerAccount.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/BrokerAccountRegistry.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/BrokerFactory.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/DeploymentRecord.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/RunConfigSnapshot.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/RunManager.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/PromoteService.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/SqliteDeploymentStore.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/ControlPlaneServer.java`
- `data/runtime/broker-accounts.json`
- `docs/testing.md`
- `docs/prop-shop-runbook.md`

## Change Log

- 2026-05-30: Story 16.9 implemented — multi-account broker routing
