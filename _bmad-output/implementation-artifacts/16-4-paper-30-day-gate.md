# Story 16.4: Gate 30 jours paper avant LIVE (prop-shop)

Status: review

## Story

As a Martin,
I want LIVE promotion blocked until 30 calendar days of **PAPER_OANDA**,
So that live capital is not rushed (PS-GR4).

## Acceptance Criteria

1. **AC1 — Block early LIVE:** PAPER_OANDA deployment &lt; 30 days → LIVE promote fails with elapsed days in `paper_duration_days` gate.
2. **AC2 — Stub excluded:** PAPER_STUB duration does not count toward paper period.
3. **AC3 — Lineage:** Re-promote to PAPER while on PAPER_OANDA preserves original `promotedAt`.
4. **AC4 — Promote label:** Promote to PAPER selects `PAPER_OANDA` when OANDA creds present (or explicit request); else `PAPER_STUB`.
5. **AC5 — Tests:** `PromoteServiceTest`, `PromoteGatesTest` cover duration, lineage, and label resolution.

## Tasks / Subtasks

- [x] Task 1: PAPER promote execution label (AC: 4)
  - [x] `PromoteRequest.executionLabel` optional field
  - [x] Default PAPER_OANDA when credentials present; `oanda_credentials` gate when explicit
- [x] Task 2: Deployment lineage (AC: 3)
  - [x] `PromoteService.resolvePromotedAt` preserves PAPER_OANDA start on re-promote
- [x] Task 3: LIVE 30-day gate (AC: 1, 2)
  - [x] Existing `PromoteGates.paperDuration` + integration tests with fixed clock
- [x] Task 4: Tests + docs (AC: 5)
  - [x] Extended `PromoteServiceTest`, `PromoteGatesTest`
  - [x] `docs/testing.md` paper period gate section
- [x] Task 5: Verify build
  - [x] `mvn test -pl trading-runtime -am -Dtest=PromoteGatesTest,PromoteServiceTest,ControlPlaneServerTest`

## Dev Notes

- Gate logic from Story 15.5; this story wires promote → PAPER_OANDA and documents lineage semantics.
- Tests inject `BooleanSupplier` for OANDA credential presence (deterministic CI without env creds).

## Dev Agent Record

### Agent Model Used

Composer

### Completion Notes List

- Promote to PAPER now resolves PAPER_OANDA vs PAPER_STUB from credentials or explicit label
- Re-promote on PAPER_OANDA keeps paper clock running from first OANDA deployment date
- LIVE blocked at 14 days with numeric reason; passes at 31 days

### File List

- `trading-runtime/src/main/java/com/martinfou/trading/runtime/PromoteService.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/PromoteGates.java`
- `trading-runtime/src/test/java/com/martinfou/trading/runtime/PromoteServiceTest.java`
- `trading-runtime/src/test/java/com/martinfou/trading/runtime/PromoteGatesTest.java`
- `trading-runtime/src/test/java/com/martinfou/trading/runtime/ControlPlaneServerTest.java`
- `docs/testing.md`
- `_bmad-output/implementation-artifacts/16-4-paper-30-day-gate.md`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`

## Change Log

- 2026-05-30: Story 16.4 implemented — PAPER_OANDA promote label, deployment lineage, 30-day LIVE gate tests
