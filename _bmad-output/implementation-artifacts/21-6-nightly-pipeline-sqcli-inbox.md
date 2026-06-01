# Story 21.6 — Pipeline nightly sqcli → inbox

Status: done

Epic: 21 — Pont StrategyQuant CLI

## Story

As Martin,
I want one command to run sqcli maintenance then drain the inbox,
so that SQ generation and TB validation chain nightly without babysitting.

## Acceptance Criteria

- [x] **AC1** — `scripts/sq-nightly.sh` (or `SqNightlyPipeline.main`) runs under mutex: update-data → list-databanks
- [x] **AC2** — Optional copy from `SQ_EXPORT_DIR` glob `*.xml` → `data/sq-inbox/pending/`
- [x] **AC3** — Invokes `SqInboxProcessor` after export step
- [x] **AC4** — Prints summary : sqcli exit codes, processed/passed/failed/dlq counts
- [x] **AC5** — `--caffeinate` flag wraps with `caffeinate -i` on Mac (documented)
- [x] **AC6** — FR-SQ3 ; documented in `docs/contributing.md`

## Tasks

- [x] `SqNightlyPipeline` orchestrator class
- [x] Shell wrapper for cron/launchd
- [x] End-to-end manual test checklist (SQ_HOME required)

## Dev Notes

- Depends on 21.2, 21.4, 21.5
- Export path is environment-specific — do not hardcode Martin's SQ install path
- launchd example optional in docs
- Extracted shared `SqBridgePaths.resolveRepoRoot()` (deferred from 21-5 CR)

## File List

- `trading-parser/.../bridge/SqBridgePaths.java`
- `trading-parser/.../bridge/SqInboxBatchResult.java`
- `trading-parser/.../bridge/SqNightlyOptions.java`
- `trading-parser/.../bridge/SqNightlyResult.java`
- `trading-parser/.../bridge/SqNightlyPipeline.java`
- `trading-parser/.../bridge/SqInboxProcessor.java` (runBatch + dlq/failed counts)
- `trading-parser/.../bridge/SqJobRunner.java` (shared resolveRepoRoot)
- `trading-parser/src/test/.../SqNightlyPipelineTest.java`
- `trading-parser/src/test/.../SqBridgePathsTest.java`
- `scripts/sq-nightly.sh`
- `docs/contributing.md`, `AGENTS.md`

## Change Log

- 2026-05-31: Story 21-6 — SqNightlyPipeline + sq-nightly.sh + docs + tests.
- 2026-05-31: CR patches — empty args run full pipeline, tests.
- 2026-05-31: Story marked done.

## References

- Story 21-5 `SqJobRunner`
- Story 21-2 `SqInboxProcessor`

## Dev Agent Record

### Completion Notes

- Orchestrator runs `update-data` then `list-databanks` via `SqJobRunner` (mutex per job).
- `SQ_EXPORT_DIR` or `--export-dir` copies `*.xml` into pending before inbox drain.
- `SqInboxBatchResult` exposes passed/failed/dlq counts for summary output.
- 120 `trading-parser` tests green.

### Manual test checklist

1. Set `SQ_HOME` to valid StrategyQuant install.
2. `./scripts/sq-nightly.sh --dry-run --skip-inbox` → exit 0, job summary.
3. Place XML in `SQ_EXPORT_DIR` or `pending/` → run with `--skip-jobs` or full pipeline.
4. Verify `passed/` / `failed/` / `dlq/` and stdout summary.
5. Schedule cron with `--caffeinate` on Mac.

### Review Findings (2026-05-31)

- [x] [Review][Patch] Args vides → help au lieu du pipeline — retirer `args.length == 0` du trigger help [`SqNightlyPipeline.java:146`]
- [x] [Review][Patch] Test — run par défaut sans args (jobs + inbox selon flags) [`SqNightlyPipelineTest`]
- [ ] [Review][Defer] Job sqcli échoué — pipeline continue export + inbox ; fail-fast optionnel avant inbox
- [ ] [Review][Defer] Export shallow — `Files.list` top-level seulement, pas glob récursif
- [ ] [Review][Defer] Sidecar manifest non copié avec XML exporté — regénéré via `ensureSidecar`
- [ ] [Review][Dismiss] Mutex par job (21-5) — pas un lock unique pour toute la phase sqcli
- [ ] [Review][Dismiss] `resolveRepoRoot` délégué dans SqJobRunner/SqInboxProcessor — acceptable
