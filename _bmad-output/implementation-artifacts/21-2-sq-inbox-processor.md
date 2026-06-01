# Story 21.2 — SqInboxProcessor ingest automatique

Status: done

Epic: 21 — Pont StrategyQuant CLI

## Story

As Martin,
I want a CLI that processes pending SQ XML through parse and backtest,
so that I get pass/fail classification without manual Maven steps.

## Acceptance Criteria

- [x] **AC1** — `SqInboxProcessor.main` scans `data/sq-inbox/pending/*.xml`
- [x] **AC2** — Parse via `SqXmlParser` → `StrategyConfig` ; evaluate or delegate backtest (interpreter path until 2-9 codegen)
- [x] **AC3** — Success → move to `passed/` + write `*-result.json` (metrics summary)
- [x] **AC4** — Failure → `failed/` ; DLQ rules from 21.3 when integrated
- [x] **AC5** — CLI args : `--symbol`, `--bars`, `--capital`, `--data-path` (defaults documented)
- [x] **AC6** — Integration test with `sqimported` XML fixture ; no control plane required
- [x] **AC7** — `mvn exec:java -pl trading-parser -Dexec.mainClass=…SqInboxProcessor` documented in AGENTS.md or contributing

## Tasks

- [x] Implement `SqInboxProcessor` orchestration
- [x] Backtest via `SqInterpretedStrategy` + `RunContext` (no `trading-examples` dependency)
- [x] File move + manifest sidecar moves with XML
- [x] Tests + docs

## Dev Notes

- **Dependency order:** 21.1 (manifest) → 21.2 ; 21.3 can land in parallel if validation is stubbed first
- Prefer interpreter/evaluator path over requiring Java codegen (2-9)
- FR-SQ1
- Empty SQ `Strategy@name` in fixture is OK; manifest id used as strategy name for backtest

## File List

- `trading-parser/.../bridge/SqInboxProcessor.java`
- `trading-parser/.../bridge/SqInboxOptions.java`
- `trading-parser/.../bridge/SqInboxResult.java`
- `trading-parser/.../bridge/SqInboxResultIO.java`
- `trading-parser/.../bridge/SqInboxTransfers.java`
- `trading-parser/src/test/.../SqInboxProcessorTest.java`
- `trading-parser/pom.xml` (trading-backtest, trading-data, slf4j-simple, exec plugin)
- `AGENTS.md`, `docs/contributing.md`

## Change Log

- 2026-05-31: CR patches — single XML read, atomic result JSON, move without silent overwrite.
- 2026-05-31: Revue CR patches appliqués ; statut → done.

## References

- Epic 2 conditions/actions evaluators
- `RunBacktest` (Epic 12)

### Review Findings (2026-05-31)

- [x] [Review][Patch] Double lecture XML — `ensureSidecar(xml, bytes)` + parse `ByteArrayInputStream` [`SqInboxProcessor.java`]
- [x] [Review][Patch] `SqInboxResultIO.write` atomique + parent null safe [`SqInboxResultIO.java`]
- [x] [Review][Patch] `moveToFolder` refuse destination existante [`SqInboxTransfers.java`]
- [x] [Review][Defer] Critères PASS autres que « pas d’exception » — pas de seuil trades/return dans AC 21.2 ; 21.3+ ou gate promote
- [x] [Review][Defer] Concurrence / taille XML / confinement chemins — reportés depuis 21.1 (`deferred-work.md`)
