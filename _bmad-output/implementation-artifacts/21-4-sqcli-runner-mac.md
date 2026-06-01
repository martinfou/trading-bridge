# Story 21.4 — SqCli runner (Mac)

Status: done

Epic: 21 — Pont StrategyQuant CLI

## Story

As Martin,
I want a Java wrapper that locates and runs StrategyQuant `sqcli` on Mac from `SQ_HOME`,
so that SQ CLI jobs can be scripted without fragile shell paths.

## Acceptance Criteria

- [x] **AC1** — `SqCliPaths` résout `SQ_HOME` (env) et binaire `sqcli` / `sqcli.exe`
- [x] **AC2** — `SqCliRunner.run` exécute une commande sqcli ; capture exit code + stdout/stderr
- [x] **AC3** — `--dry-run` n’exécute pas le binaire (tests CI offline)
- [x] **AC4** — Erreur claire si `SQ_HOME` absent ou binaire introuvable
- [x] **AC5** — CLI `SqCliRunner.main` : `--sq-home`, `--dry-run`, `--timeout-secs`, args après `--`
- [x] **AC6** — Tests avec faux binaire shell ; pas de SQ installé requis
- [x] **AC7** — Documenté dans `docs/contributing.md` + `AGENTS.md`

## Tasks

- [x] `SqCliPaths`, `SqCliRunner`, `SqCliRunResult`, `SqCliOptions`
- [x] `SqCliRunner.main`
- [x] Tests + docs

## Dev Notes

- Mac : préférer `sqcli` sans extension ; fallback `sqcli.exe`
- Working directory = `SQ_HOME` (engine SQ)
- FR-SQ1 ; story 21-5 ajoutera registry scripts

## File List

- `trading-parser/.../bridge/SqCliPaths.java`
- `trading-parser/.../bridge/SqCliNotFoundException.java`
- `trading-parser/.../bridge/SqCliOptions.java`
- `trading-parser/.../bridge/SqCliRunResult.java`
- `trading-parser/.../bridge/SqCliRunner.java`
- `trading-parser/src/test/.../SqCliRunnerTest.java`
- `docs/contributing.md`, `AGENTS.md`

## Change Log

- 2026-05-31: Story 21-4 — SqCliRunner Mac wrapper + dry-run + tests.
- 2026-05-31: CR patches — waitFor order, cliBinaryPath, stderr javadoc, tests + redirect doc.
- 2026-05-31: Revue CR patches appliqués ; statut → done.

## References

- https://strategyquant.com/doc/cli-command-line/introduction-to-cli/
- Story 21-2 `SqInboxProcessor`

### Review Findings (2026-05-31)

- [x] [Review][Patch] Deadlock pipe — `waitFor()` puis `readAllBytes()` [`SqCliRunner.java`]
- [x] [Review][Patch] Dry-run binaire — `SqCliPaths.cliBinaryPath` [`SqCliPaths.java`]
- [x] [Review][Patch] `SqCliRunResult.stderr` — javadoc fusion stderr→stdout [`SqCliRunResult.java`]
- [x] [Review][Patch] Test exit non nul + doc redirect `>` SQ [`SqCliRunnerTest.java`, usage]
- [x] [Review][Defer] `SQ_HOME` via symlink `.app` — couvert par `contributing.md` ; pas de détection auto bundle Mac
- [x] [Review][Defer] Dry-run exige répertoire `SQ_HOME` valide — `--sq-home` ou env requis même offline
- [x] [Review][Dismiss] Args passés en liste ProcessBuilder — pas d’injection shell
