# Story 17.11: Labels mode exécution dans summary UI (prop-shop)

Status: review

## Story

As a Martin,
I want enhanced execution label styling in the control room,
So that stub replay is never mistaken for broker execution at a glance (PS-GR11 UI polish).

## Acceptance Criteria

1. **AC1 — Catalog:** `ExecutionLabelCatalog` exposes display name, category, badge colors for all labels.
2. **AC2 — Summary:** `GET /control/summary` includes `executionLabelCatalog` and per-run `executionLabelMeta`.
3. **AC3 — Run detail:** `GET /api/runs/{runId}` includes `executionLabelMeta`.
4. **AC4 — Evidence:** JSONL metadata and HTML due diligence use the same presentation metadata.
5. **AC5 — Stub warning:** `PAPER_STUB` meta includes `stubWarning: true` and distinct amber styling.
6. **AC6 — Tests:** `ExecutionLabelCatalogTest`, updated summary/export/HTML tests.

## Tasks / Subtasks

- [x] Task 1: ExecutionLabelPresentation + ExecutionLabelCatalog (AC: 1, 5)
- [x] Task 2: Wire API surfaces (AC: 2, 3)
  - [x] ControlSummaryService, ControlPlaneServer, DeploymentRecord
- [x] Task 3: Evidence + HTML (AC: 4)
  - [x] EvidencePackExporter, DueDiligenceHtmlExporter badge styling
- [x] Task 4: Tests + docs
- [x] Task 5: Verify build

## Dev Agent Record

### File List

- `trading-runtime/src/main/java/com/martinfou/trading/runtime/ExecutionLabelPresentation.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/ExecutionLabelCatalog.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/ControlSummaryService.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/ControlPlaneServer.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/DeploymentRecord.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/EvidencePackExporter.java`
- `trading-runtime/src/main/java/com/martinfou/trading/runtime/DueDiligenceHtmlExporter.java`
- `docs/testing.md`

## Change Log

- 2026-05-30: Story 17.11 implemented — execution label catalog and badge metadata across API/HTML
