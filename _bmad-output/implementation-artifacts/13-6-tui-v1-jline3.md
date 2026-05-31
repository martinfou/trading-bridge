# Story 13.6 — TUI v1 (JLine3 slash commands + stream)

Status: review

## Story

As Martin, I want a terminal workshop (TUI) connected to the Java control plane so I can list strategies, launch backtests, promote, and tail run events without curl.

## Acceptance Criteria

- [x] **AC1** — Module `trading-tui` with JLine3 REPL and `TradingTuiMain`
- [x] **AC2** — Slash commands: `/list`, `/status`, `/backtest`, `/promote`, `/run`, `/events`, `/kill`, `/help`, `/quit`
- [x] **AC3** — HTTP client targets `CONTROL_PLANE_URL` (default `http://localhost:8080`)
- [x] **AC4** — `/backtest` polls run until terminal status and prints last events
- [x] **AC5** — Unit tests for command parsing and help

## Tasks

- [x] Add `trading-tui` Maven module + JLine dependency
- [x] Implement `ControlPlaneClient` (REST)
- [x] Implement `TuiCommandHandler` + `TradingTuiMain`
- [x] Tests `TuiCommandHandlerTest`
- [x] Document launch in `docs/testing.md`

## Dev Agent Record

### Completion Notes

TUI is a thin client — no trading logic. Requires `ControlPlaneMain` running. Promote/kill delegate to existing gates on the server.

### File List

- `trading-tui/pom.xml`
- `trading-tui/src/main/java/com/martinfou/trading/tui/*.java`
- `trading-tui/src/test/java/com/martinfou/trading/tui/TuiCommandHandlerTest.java`
- `pom.xml` (module + jline BOM)
- `docs/testing.md`

## Change Log

- 2026-05-30: Initial TUI v1 implementation
