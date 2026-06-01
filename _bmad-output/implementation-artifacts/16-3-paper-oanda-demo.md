# Story 16.3: Paper trading OANDA demo (prop-shop)

Status: review

## Story

As a Martin,
I want strategies running against OANDA demo with journaled events,
So that paper proves broker execution before LIVE (PS-GR1).

## Acceptance Criteria

1. **AC1 — REST client:** `OandaRestClient` + `HttpOandaRestClient` in `trading-data`.
2. **AC2 — Broker adapter:** `OandaBroker` in `trading-broker` implements `Broker`.
3. **AC3 — PAPER_OANDA runs:** `StartRunRequest.executionLabel=PAPER_OANDA` routes orders via broker; events ORDER_SUBMITTED/FILL/REJECT in event store.
4. **AC4 — Credentials:** Env-only (`OANDA_API_KEY` / `OANDA_API_TOKEN`, `OANDA_ACCOUNT_ID`); rejected without creds.
5. **AC5 — Distinct from stub:** API exposes `executionLabel: PAPER_OANDA` vs `PAPER_STUB`.
6. **AC6 — Tests:** `StubOandaRestClientTest`, `OandaBrokerTest`, `PaperOandaRunExecutorTest`.

## Tasks / Subtasks

- [x] Task 1: OANDA REST layer (AC: 1)
  - [x] `OandaRestClient`, `HttpOandaRestClient`, `StubOandaRestClient`
- [x] Task 2: OandaBroker (AC: 2)
  - [x] MARKET order routing, positions, account state, broker events
- [x] Task 3: Runtime PAPER_OANDA path (AC: 3, 4, 5)
  - [x] `RunConfigSnapshot.executionLabel`, `PaperOandaRunExecutor`, `RunManager` branch
  - [x] `BrokerProvider.oandaBrokerFromEnvironment()`
- [x] Task 4: Tests (AC: 6)
  - [x] Unit tests with stub client + FakeBroker paper executor test
- [x] Task 5: Verify build
  - [x] `mvn test -pl trading-broker,trading-data,trading-runtime -am`

## Dev Notes

- Live OANDA integration test gated by env (`@Tag("oanda")` optional follow-up).
- `PaperOandaRunExecutor` replays bars and submits strategy orders to broker (worker-local execution path).
- Promote to PAPER with `PAPER_OANDA` deployment label is Story 16.4 follow-up.

## Dev Agent Record

### Agent Model Used

Composer

### Completion Notes List

- Split HTTP (`trading-data`) from broker adapter (`trading-broker`) per architecture
- PAPER_OANDA runs journal broker events as RunEvents (ORDER_SUBMITTED, FILL, REJECT)

### File List

- `trading-data/src/main/java/com/martinfou/trading/data/oanda/*.java`
- `trading-broker/src/main/java/com/martinfou/trading/broker/OandaBroker.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/PaperOandaRunExecutor.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/BrokerProvider.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/RunConfigSnapshot.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/RunManager.java`
- `trading-data/src/test/java/com/martinfou/trading/data/oanda/StubOandaRestClientTest.java`
- `trading-broker/src/test/java/com/martinfou/trading/broker/OandaBrokerTest.java`
- `trading-runtime/src/test/java/com/martinfou/trading/runtime/PaperOandaRunExecutorTest.java`
- `_bmad-output/implementation-artifacts/16-3-paper-oanda-demo.md`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`

## Change Log

- 2026-05-30: Story 16.3 implemented — OANDA REST client, OandaBroker, PAPER_OANDA runtime path
