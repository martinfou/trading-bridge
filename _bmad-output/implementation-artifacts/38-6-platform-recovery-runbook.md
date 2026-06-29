# Story 38.6: Platform Recovery Runbook

Status: done

## Story

As a DevOps Engineer / System Administrator,
I want a Platform Recovery Runbook,
So that I can quickly recover the system from complete failure (hardware crash, process termination) and restore state from database backups.

## Acceptance Criteria

1. **AC1 — Disaster Recovery:** Detail steps to restore SQLite files from cold/warm backups.
2. **AC2 — State Restoration:** Document how the system auto-restores active runs and synchronizes position states upon reboot.
3. **AC3 — Documentation:** Save as `docs/reliability-engineering.md` §6.

## Tasks / Subtasks

- [x] Task 1: Document restore commands for SQLite event stores
- [x] Task 2: Describe active runs restoration behavior
- [x] Task 3: Author final platform recovery documentation in `docs/reliability-engineering.md`

## Dev Agent Record

### Agent Model Used

Gemini 1.5 Pro

### File List

- [reliability-engineering.md](file:///Volumes/T7/src/trading-bridge/docs/reliability-engineering.md)

## Change Log

- 2026-06-28: Story completed — Platform recovery runbook documented in docs/reliability-engineering.md.
