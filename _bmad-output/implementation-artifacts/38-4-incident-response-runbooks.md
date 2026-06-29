# Story 38.4: Incident Response Runbooks

Status: done

## Story

As an On-Call Operator,
I want structured incident response runbooks,
So that I can resolve common live production issues (broker disconnects, rate limits, corrupt databases) quickly and safely.

## Acceptance Criteria

1. **AC1 — Incident Flow:** Define step-by-step diagnostic and remediation flows for OANDA and IBKR connection failures.
2. **AC2 — Rate Limit Recovery:** Detail exponential backoff and rate-limit guard procedures.
3. **AC3 — Database Recovery:** Provide instructions for SQLite database corruption checks and recoveries.
4. **AC4 — Documentation:** Save as `docs/reliability-engineering.md` §2 and `docs/operations-manifest.md` §5.

## Tasks / Subtasks

- [x] Task 1: Document broker reconnect procedures
- [x] Task 2: Detail SQLite database integrity check and repair commands
- [x] Task 3: Establish incident response flowcharts and documentation

## Dev Agent Record

### Agent Model Used

Gemini 1.5 Pro

### File List

- [reliability-engineering.md](file:///Volumes/T7/src/trading-bridge/docs/reliability-engineering.md)
- [operations-manifest.md](file:///Volumes/T7/src/trading-bridge/docs/operations-manifest.md)

## Change Log

- 2026-06-28: Story completed — Incident response runbooks documented.
