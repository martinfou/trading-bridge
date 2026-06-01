# Story 16.2: Interface Broker skeleton (prop-shop)

Status: review

## Story

As a developer,
I want a minimal shared `Broker` interface before OANDA integration,
So that execution code does not hard-code OANDA types (PS-GR12).

## Acceptance Criteria

1. **AC1 — Broker interface:** `submitOrder`, `getPositions`, `getAccountState`, `connect`/`disconnect`/`reconnect`.
2. **AC2 — BrokerEvent:** `ORDER_SUBMITTED`, `FILL`, `REJECT` types with immutable payload.
3. **AC3 — FakeBroker:** In-memory implementation satisfies contract in tests.
4. **AC4 — Runtime dependency:** `trading-runtime` depends on `trading-broker` interface only via `BrokerProvider`.
5. **AC5 — Credentials:** `BrokerCredentials.oandaFromEnvironment()` reads env vars only (NFR2).
6. **AC6 — RunEvent alignment:** `RunEventType.REJECT` added for future journal mapping (16.7+).
7. **AC7 — Tests:** `FakeBrokerTest`, `BrokerProviderTest`.

## Tasks / Subtasks

- [x] Task 1: trading-broker module (AC: 1, 2, 3, 5)
  - [x] `Broker`, `FakeBroker`, `BrokerEvent`, `AccountState`, `OrderSubmitResult`, `BrokerCredentials`
- [x] Task 2: Runtime wiring (AC: 4)
  - [x] `trading-runtime` → `trading-broker` dependency + `BrokerProvider`
- [x] Task 3: Event contract (AC: 6)
  - [x] `RunEventType.REJECT`
- [x] Task 4: Tests (AC: 7)
  - [x] `FakeBrokerTest`, `BrokerCredentialsTest`, `BrokerProviderTest`
- [x] Task 5: Verify build
  - [x] `mvn test -pl trading-broker,trading-runtime -am -Dtest=FakeBrokerTest,BrokerCredentialsTest,BrokerProviderTest`

## Dev Notes

- HTTP OANDA client stays in `trading-data`; `OandaBroker` implementation is Story 16.3.
- `FakeBroker` fills MARKET orders immediately at order price (dev/test only).

## Dev Agent Record

### Agent Model Used

Composer

### Completion Notes List

- Introduced shared `Broker` contract in `trading-broker` with fake implementation for tests
- Runtime consumes broker via `BrokerProvider` — no OANDA types in runtime

### File List

- `trading-broker/src/main/java/com/martinfou/trading/broker/Broker.java`
- `trading-broker/src/main/java/com/martinfou/trading/broker/FakeBroker.java`
- `trading-broker/src/main/java/com/martinfou/trading/broker/BrokerEvent.java`
- `trading-broker/src/main/java/com/martinfou/trading/broker/BrokerEventType.java`
- `trading-broker/src/main/java/com/martinfou/trading/broker/AccountState.java`
- `trading-broker/src/main/java/com/martinfou/trading/broker/OrderSubmitResult.java`
- `trading-broker/src/main/java/com/martinfou/trading/broker/BrokerCredentials.java`
- `trading-broker/src/test/java/com/martinfou/trading/broker/FakeBrokerTest.java`
- `trading-broker/src/test/java/com/martinfou/trading/broker/BrokerCredentialsTest.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/BrokerProvider.java`
- `trading-runtime/pom.xml`
- `trading-runtime/src/test/java/com/martinfou/trading/runtime/BrokerProviderTest.java`
- `trading-backtest/src/main/java/com/martinfou/trading/backtest/events/RunEventType.java`
- `_bmad-output/implementation-artifacts/16-2-broker-interface-skeleton.md`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`

## Change Log

- 2026-05-30: Story 16.2 implemented — Broker interface, FakeBroker, runtime dependency
