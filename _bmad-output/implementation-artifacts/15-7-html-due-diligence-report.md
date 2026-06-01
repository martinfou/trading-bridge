# Story 15.7: Rapport HTML due diligence (prop-shop)

Status: review

## Story

As a Martin,
I want a self-contained HTML report per run or period,
So that I can share external due diligence when submission-ready (PS-GR16).

## Acceptance Criteria

1. **AC1 — Endpoint:** `GET /api/runs/{runId}/export?format=html` returns self-contained HTML.
2. **AC2 — Content:** Report includes equity summary, trade table (fills when present), Sharpe/PF/max DD where computable, and config hash.
3. **AC3 — Disclaimer:** Prominent banner states execution mode (backtest / stub / OANDA / LIVE).
4. **AC4 — Offline:** No external CDN dependencies; renders without control plane after download.
5. **AC5 — JSONL preserved:** Default export (no format param) remains JSONL evidence pack.

## Tasks / Subtasks

- [x] Task 1: DueDiligenceHtmlExporter (AC: 2–4)
  - [x] Parse RUN_STARTED/RUN_ENDED/FILL events; execution-label disclaimers
- [x] Task 2: HTTP route (AC: 1, 5)
  - [x] `?format=html` on existing export endpoint
- [x] Task 3: RUN_ENDED enrichment (AC: 2)
  - [x] `sharpeRatio`, `profitFactor`, `winRatePct` in RunContext RUN_ENDED payload
- [x] Task 4: Tests + docs
  - [x] `DueDiligenceHtmlExporterTest`, `ControlPlaneServerTest.export_html_*`
- [x] Task 5: Verify build
  - [x] `mvn test -pl trading-runtime -am -Dtest=DueDiligenceHtmlExporterTest,ControlPlaneServerTest`

## Dev Agent Record

### Agent Model Used

Composer

### File List

- `trading-runtime/src/main/java/com/martinfou/trading/runtime/DueDiligenceHtmlExporter.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/ControlPlaneServer.java`
- `trading-backtest/src/main/java/com/martinfou/trading/backtest/RunContext.java`
- `trading-runtime/src/test/java/com/martinfou/trading/runtime/DueDiligenceHtmlExporterTest.java`
- `trading-runtime/src/test/java/com/martinfou/trading/runtime/ControlPlaneServerTest.java`
- `docs/testing.md`
- `_bmad-output/implementation-artifacts/15-7-html-due-diligence-report.md`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`

## Change Log

- 2026-05-30: Story 15.7 implemented — self-contained HTML due diligence export
