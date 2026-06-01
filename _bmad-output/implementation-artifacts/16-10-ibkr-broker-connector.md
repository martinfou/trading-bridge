# Story 16.10: Connecteur IBKR paper/live (prop-shop)

Status: review

## Story

As a Martin,
I want IBKR execution via TWS or IB Gateway after OANDA is stable,
So that I am not locked to one broker (PS-GR17).

## Acceptance Criteria

1. **AC1 — IbkrBroker:** Implements shared `Broker` interface via `IbkrGatewayClient`.
2. **AC2 — Labels:** `PAPER_IBKR` and `LIVE_IBKR` execution paths wired in runtime.
3. **AC3 — Events:** FILL / REJECT / RUN_STARTED journal identically to OANDA path.
4. **AC4 — Promote gates:** OANDA 30-day paper rule unchanged (`PAPER_OANDA` only).
5. **AC5 — Tests:** `IbkrBrokerTest`, `BrokerRunExecutorTest`; OANDA tests independent.

## Tasks / Subtasks

- [x] Task 1: trading-data ibkr client layer (AC: 1)
  - [x] `IbkrGatewayClient`, `StubIbkrGatewayClient`, `TcpIbkrGatewayClient`, `IbkrConnectionConfig`
- [x] Task 2: IbkrBroker adapter (AC: 1–3)
- [x] Task 3: Runtime wiring (AC: 2)
  - [x] `ExecutionLabel.PAPER_IBKR`, `BrokerFactory`, `BrokerAccountRegistry` IBKR provider
- [x] Task 4: Tests + docs
- [x] Task 5: Verify build

## Dev Agent Record

### File List

- `trading-data/src/main/java/com/martinfou/trading/data/ibkr/*`
- `trading-broker/src/main/java/com/martinfou/trading/broker/IbkrBroker.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/ExecutionLabel.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/BrokerProvider.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/BrokerFactory.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/BrokerAccountRegistry.java`
- `data/runtime/broker-accounts.json`
- `docs/testing.md`

## Change Log

- 2026-05-30: Story 16.10 implemented — IBKR broker adapter and PAPER_IBKR/LIVE_IBKR paths
