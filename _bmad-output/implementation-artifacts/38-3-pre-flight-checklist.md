# Story 38.3: Pre-Flight Checklist

Status: done

## Story

As an Operator,
I want a comprehensive pre-flight checklist,
So that I can verify all configurations, credentials, API tokens, and systems are correct before starting any paper or live run.

## Acceptance Criteria

1. **AC1 — Checklist Items:** Cover environment variables, connection checks, log configurations, risk limit checks, data directory verification.
2. **AC2 — Executable commands:** List validation commands that can be run from the CLI (e.g. env checks, ping, API ping).
3. **AC3 — Documentation:** Document in `docs/operations-manifest.md` §3 and `docs/reliability-engineering.md` §4.

## Tasks / Subtasks

- [x] Task 1: Compile required pre-flight checks (OANDA, filesystems, database)
- [x] Task 2: Write exact terminal validation commands
- [x] Task 3: Author checklists in docs

## Dev Agent Record

### Agent Model Used

Gemini 1.5 Pro

### File List

- [operations-manifest.md](file:///Volumes/T7/src/trading-bridge/docs/operations-manifest.md)
- [reliability-engineering.md](file:///Volumes/T7/src/trading-bridge/docs/reliability-engineering.md)

## Change Log

- 2026-06-28: Story completed — Pre-flight checklists documented in operations-manifest.md and reliability-engineering.md.
