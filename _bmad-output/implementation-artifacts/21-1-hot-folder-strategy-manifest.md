# Story 21.1 — Hot folder et manifest stratégie

Status: done

Epic: 21 — Pont StrategyQuant CLI

## Story

As Martin,
I want a standard inbox folder layout and JSON manifest schema for SQ XML drops,
so that every export is traceable before parse or backtest.

## Acceptance Criteria

- [x] **AC1** — Directories `data/sq-inbox/{pending,passed,failed,dlq}` with `.gitkeep` ; inbox content gitignored
- [x] **AC2** — `StrategyManifest` record (Jackson) : `id`, `symbol`, `timeframe`, `sqBuild`, `contentSha256`, `exportedAt` (Instant UTC)
- [x] **AC3** — Optional sidecar `*.manifest.json` beside XML in `pending/` ; auto-generate from `SqXmlFormatProbe` if missing
- [x] **AC4** — `docs/contributing.md` : Mac `SQ_HOME`, symlink staging for paths with spaces
- [x] **AC5** — Unit test round-trip manifest read/write

## Tasks

- [x] Create inbox directory layout + `.gitignore` entries
- [x] Add `StrategyManifest` + `StrategyManifestIO` in `trading-parser` … `bridge`
- [x] Wire probe-based manifest generation
- [x] Document in `docs/contributing.md`

## Dev Agent Record

### File List

- `data/sq-inbox/pending/.gitkeep`
- `data/sq-inbox/passed/.gitkeep`
- `data/sq-inbox/failed/.gitkeep`
- `data/sq-inbox/dlq/.gitkeep`
- `.gitignore`
- `pom.xml` (jackson-datatype-jsr310 in dependencyManagement)
- `trading-parser/pom.xml`
- `trading-parser/src/main/java/com/martinfou/trading/parser/bridge/StrategyManifest.java`
- `trading-parser/src/main/java/com/martinfou/trading/parser/bridge/SqInboxPaths.java`
- `trading-parser/src/main/java/com/martinfou/trading/parser/bridge/StrategyManifestFactory.java`
- `trading-parser/src/main/java/com/martinfou/trading/parser/bridge/StrategyManifestIO.java`
- `trading-parser/src/main/java/com/martinfou/trading/parser/sq/SqXmlFormatProbe.java` (public `analyze(document)`)
- `trading-parser/src/test/java/com/martinfou/trading/parser/bridge/StrategyManifestIOTest.java`
- `docs/contributing.md`

## Change Log

- 2026-05-30: Story 21-1 implemented — SQ hot folder layout + manifest sidecar (82 tests green in trading-parser).
- 2026-05-31: Code review — décision B (symbol/timeframe → 21.2) ; 8 patches laissés en action items ; statut → in-progress.
- 2026-05-31: 8 patches CR appliqués + tests ; statut → review.
- 2026-05-31: Revue post-patch propre ; statut → done.

### Review Findings (2026-05-31)

- [x] [Review][Decision] Symbol / timeframe — **décision : B** — garder défauts `EUR_USD` / `UNKNOWN` jusqu’à story **21.2** (`SqInboxProcessor`)
- [x] [Review][Patch] Sidecar stale — `ensureSidecar` compare `contentSha256` aux octets XML courants
- [x] [Review][Patch] TOCTOU hash vs parse — `fromBytes` + `ByteArrayInputStream` ; une seule lecture dans `fromXml` / `ensureSidecar`
- [x] [Review][Patch] Écriture atomique — temp file + `ATOMIC_MOVE` (fallback replace)
- [x] [Review][Patch] Manifest JSON corrompu — `tryRead` + delete + regen
- [x] [Review][Patch] Valider `contentSha256` — 64 lowercase hex dans le compact ctor
- [x] [Review][Patch] `manifestPathFor` — `resolveSibling` + `write` ignore parent null
- [x] [Review][Patch] Javadoc `ensureSidecar` — comportement hash-match documenté
- [x] [Review][Patch] Tests — `ensureSidecar_regeneratesWhenXmlHashMismatch`, corrupt JSON, hash-match reuse
- [x] [Review][Defer] Concurrence deux `ensureSidecar` — last-write-wins [`StrategyManifestIO.java:34-38`] — deferred, story 21.2 processor
- [x] [Review][Defer] Limite taille XML / OOM — [`StrategyManifestFactory.java:22`] — deferred, inbox processor 21.2
- [x] [Review][Defer] Confinement chemins hors `data/sq-inbox/` — [`SqInboxPaths.java`] — deferred, 21.2 CLI avec repoRoot
- [x] [Review][Defer] `exportedAt` = mtime fichier, pas date export SQ — [`StrategyManifestFactory.java:25`] — deferred, pas requis AC 21.1

### Review Findings (2026-05-31 post-patch)

- [x] [Review][Dismiss] Métadonnées sidecar figées quand hash OK — comportement voulu (`ensureSidecar_reusesWhenHashMatches`) ; id/symbol custom conservés si `contentSha256` valide
- [x] [Review][Defer] `tryRead` avale toute `IOException` — acceptable pour déclencher regen ; logging optionnel en 21.3
