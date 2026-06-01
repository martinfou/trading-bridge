# Story 21.5 — SQ job mutex et registry scripts

Status: done

Epic: 21 — Pont StrategyQuant CLI

## Story

As Martin,
I want named sqcli jobs in a versioned registry with an exclusive file lock,
so that concurrent SQ CLI runs do not corrupt the engine state.

## Acceptance Criteria

- [x] **AC1** — `data/sq-cli/scripts/registry.json` avec jobs nommés (id, description, args)
- [x] **AC2** — `SqJobScriptRegistry` charge et résout les scripts
- [x] **AC3** — `SqJobMutex` verrou fichier `data/sq-cli/.sq-job.lock` (tryLock → `SqJobBusyException`)
- [x] **AC4** — `SqJobRunner` enchaîne mutex + `SqCliRunner` pour `--run ID`
- [x] **AC5** — CLI `--list`, `--run`, `--dry-run`, `--no-lock` (tests)
- [x] **AC6** — Tests offline : registry, mutex, dry-run job
- [x] **AC7** — Documenté `contributing.md` + `AGENTS.md`

## Tasks

- [x] Registry JSON + `SqJobScriptRegistry`
- [x] `SqJobMutex` + `SqJobRunner`
- [x] Tests + docs + `.gitignore` lock file

## File List

- `data/sq-cli/scripts/registry.json`
- `trading-parser/.../bridge/SqJobPaths.java`
- `trading-parser/.../bridge/SqJobScript.java`
- `trading-parser/.../bridge/SqJobScriptRegistry.java`
- `trading-parser/.../bridge/SqJobMutex.java`
- `trading-parser/.../bridge/SqJobBusyException.java`
- `trading-parser/.../bridge/SqJobOptions.java`
- `trading-parser/.../bridge/SqJobRunner.java`
- `trading-parser/src/test/resources/sq-cli/registry-test.json`
- `trading-parser/src/test/.../SqJob*Test.java`
- `.gitignore`, `docs/contributing.md`, `AGENTS.md`

## Change Log

- 2026-05-31: Story 21-5 — script registry + job mutex + SqJobRunner CLI.
- 2026-05-31: CR patches — dry-run skip mutex, duplicate ids, --no-lock warn, tests.
- 2026-05-31: Story marked done.

## References

- Story 21-4 `SqCliRunner`

### Review Findings (2026-05-31)

- [x] [Review][Patch] Dry-run prend le mutex — sauter le lock si `dryRun` [`SqJobRunner.java`]
- [x] [Review][Patch] `loadFromClasspath` — détection doublons id [`SqJobScriptRegistry.java`]
- [x] [Review][Patch] `--no-lock` — WARN log [`SqJobRunner.runWithMutex`]
- [x] [Review][Patch] Test — registry id dupliqué [`SqJobScriptRegistryTest.java`]
- [x] [Review][Defer] `resolveRepoRoot` dupliqué vs `SqInboxProcessor` — extraire helper commun en 21-6
- [x] [Review][Defer] `--list` n’affiche pas les args — suffisant pour 21-5
- [x] [Review][Dismiss] Verrou tenu pendant toute l’exécution sqcli — comportement voulu
