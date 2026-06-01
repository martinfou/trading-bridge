# Story 21.8 — Boucle fitness TB→SQ indicateurs externes

Status: done

Epic: 21 — Pont StrategyQuant CLI

## Story

As Martin,
I want backtest scores exported as SQ external indicator CSV and re-imported via sqcli,
so that StrategyQuant Retester can filter candidates using TB validation.

## Acceptance Criteria

- [x] **AC1** — `TbFitnessCsvExporter` writes SQ-compatible CSV (comma sep, `dd/MM/yyyy`, `HH:mm:ss`, no path spaces)
- [x] **AC2** — Columns documented : at minimum timestamp + strategy key + sharpe + profitFactor + maxDrawdown + compositeScore
- [x] **AC3** — `--sq-feedback` on `SqInboxProcessor` or nightly pipeline triggers export + import
- [x] **AC4** — `scripts/sq/commands-ext-indicator-setup.txt` : `-extindicators action=add name=tbFitness values=…`
- [x] **AC5** — Import via `-extindicators action=import name=tbFitness file=…` under mutex
- [x] **AC6** — E2E test skipped without SQ_HOME ; manual verification checklist in dev notes
- [x] **AC7** — FR-SQ4

## Tasks

- [x] CSV exporter + schema doc in `docs/sq-cli-bridge.md`
- [x] Integrate with sqcli scripts from 21.5
- [x] Manual E2E checklist

## Dev Notes

- Depends on 21.4, 21.5, 21.6 (or at minimum inbox metrics from 21.2)
- SQ Build 142+ multi-value external indicators
- Brainstorm #8, #9, #73

### Implementation

- `SqInboxResult` extended with fitness fields; populated from `BacktestResult` in `finishPassed`
- `TbFitnessCollector` → `TbFitnessCsvExporter` → `SqFitnessFeedbackService` (mutex import)
- Registry job `setup-tb-fitness`; import uses dynamic `file=` path (not in registry)
- CLI: `--sq-feedback` on `SqInboxProcessor` and `SqNightlyPipeline`

### Review Findings

- [x] [Review][Patch] SqInboxProcessor feedback always skips mutex [`SqInboxProcessor.java:62`] — `toJobOptions(false, true, …)` hardcodes `skipMutex=true`; standalone `--sq-feedback` violates AC5 (import must run under mutex)
- [x] [Review][Patch] setupExitCode never populated [`SqFitnessFeedbackService.java:65-72`] — both `setupExitCode` and `importExitCode` set from import result; setup outcome lost
- [x] [Review][Patch] NaN/Infinity not sanitized in CSV export [`TbFitnessCsvExporter.java:69-71`] — `PerformanceMetrics` can yield NaN; `"NaN"` in CSV may break SQ import
- [x] [Review][Defer] Control plane `process-inbox` has no `--sq-feedback` [`SqBridgeService.java:136-142`] — deferred, out of AC3 scope (CLI/nightly only)

## References

- https://strategyquant.com/doc/cli-command-line/importing-multiple-external-indicator-values-using-cli-command-2/
- `docs/sq-cli-bridge.md`
