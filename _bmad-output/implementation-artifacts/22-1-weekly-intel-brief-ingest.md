# Story 22.1 — Module trading-intelligence, ingest brief et hot-folder layout

Status: done

Epic: 22 — Weekly Strategy Builder (intelligence hebdomadaire LLM)

<!-- Hot-folder architecture 2026-06-02 — Job 1/2/3 decoupled via data/weekly-builder/ -->

## Story

As Martin,
I want weekly intel ingest and a standard hot-folder directory layout,
So that plan and compile jobs communicate only via files on disk (Job 1 → `pending/`, Job 2 → `compiled/`).

## Acceptance Criteria

1. **AC1 — Maven module:** `trading-intelligence` in reactor; deps `trading-core`, `trading-data`; no `trading-runtime` on ingest path.
2. **AC2 — Hot-folder layout:** `WeeklyBuilderPaths` (mirror `SqInboxPaths` in Epic 21) creates/resolves:
   - `data/weekly-intel/` — brief JSON (Job 1 input audit)
   - `data/weekly-builder/{pending,compiling,compiled,deployed,failed,dlq,archive}/` — `.gitkeep` only in git ; contents gitignored
3. **AC3 — WeeklyIntelBrief schema:** Jackson records, UTC `Instant`:
   - `generatedAt`, `weekStart`, `calendarEvents[]`, `newsItems[]`, `cotSnapshots[]`, `sentiment`, `contradictions[]`, `ingestStatus` (`OK`|`PARTIAL`|`FAILED`)
4. **AC4 — IngestPipeline:** `ForexFactoryScraper` (next week), `COTDataFetcher`, `OandaPositionAnalyzer` (missing creds → `PARTIAL` + warn), news stub.
5. **AC5 — Fail-fast calendar:** scrape failure → `FAILED`, no brief for LLM (Job 1 stops before DeepSeek in 22.2).
6. **AC6 — CLI:** `WeeklyIntelIngestMain` writes `data/weekly-intel/brief-YYYY-MM-DD.json`.
7. **AC7 — WeeklyAnalysisRunner** unchanged.
8. **AC8 — Tests:** fixtures only; test `WeeklyBuilderPaths` resolves all folders.

## Tasks / Subtasks

- [x] Task 1: Scaffold module (AC: 1)
- [x] Task 2: `WeeklyBuilderPaths` + data dirs (AC: 2)
  - [x] Mirror pattern from `SqInboxPaths` / `data/sq-inbox/`
  - [x] `.gitignore` entries for `data/weekly-intel/*`, `data/weekly-builder/*`
- [x] Task 3: Domain models + IO (AC: 3)
- [x] Task 4: Ingest steps (AC: 4, 5)
- [x] Task 5: CLI (AC: 6)
- [x] Task 6: Tests (AC: 8)

## Dev Notes

### Architecture compliance

- New module sits beside `trading-data` — **ingest only**, no LLM, no control plane calls in 22.1.
- Dependency graph must stay acyclic: `trading-intelligence` → `trading-data` → `trading-core`. Do **not** add `trading-intelligence` → `trading-runtime`.
- Time: UTC everywhere (`Instant`, `Clock` injectable for tests). Week boundary = Monday 00:00 UTC per `TimeConventions` patterns in `trading-core`.

### Reuse existing connectors (READ before writing)

| Class | Module | Use |
|-------|--------|-----|
| `ForexFactoryScraper` | trading-data | Calendar S+1; see `fetchWeek`, `highImpact` |
| `COTDataFetcher` | trading-data | Positioning snapshots |
| `OandaPositionAnalyzer` | trading-data | Retail sentiment; maintenance errors on weekend — log warn |
| `EconomicCalendar` | trading-data | Fallback reference only in tests, not production ingest |
| `WeeklyAnalysisRunner` | trading-data | **Do not replace** — debug CLI |

Reference implementation pattern: `WeeklyAnalysisRunner.main` lines 22–40 (calendar), 42–60 (COT), 62–79 (OANDA).

### Week targeting

- **Target week** = calendar week starting **next Monday** after ingest run date (Friday run → next week Mon–Sun).
- Helper: `WeekBounds.nextTradingWeek(Clock)` in `trading-intelligence` (or `trading-core` if reusable — prefer intelligence module first).

### WeeklyIntelBrief JSON example (minimal)

```json
{
  "generatedAt": "2026-06-06T17:00:00Z",
  "weekStart": "2026-06-09",
  "ingestStatus": "OK",
  "calendarEvents": [
    {"eventId": "ff-2026-06-11-us-cpi", "name": "US CPI", "currency": "USD", "impact": "HIGH", "timeUtc": "2026-06-11T12:30:00Z", "source": "forexfactory"}
  ],
  "cotSnapshots": [],
  "newsItems": [],
  "sentiment": {"oandaRetail": [], "nlpScores": []},
  "contradictions": []
}
```

### Hot-folder architecture (Epic 22)

Three decoupled jobs — **22.1 only builds layout + ingest**, not LLM or compile:

| Folder | Job | Purpose |
|--------|-----|---------|
| `data/weekly-intel/` | Job 1 | `WeeklyIntelBrief` before DeepSeek |
| `pending/` | Job 1 → 2 | Approved `weekly-plan-YYYY-Www.json` |
| `compiling/` | Job 2 lock | Mutex during codegen + mvn |
| `compiled/` | Job 2 → 3 | Compile OK + manifest |
| `deployed/` | Job 3 | PAPER_OANDA done |
| `failed/` / `dlq/` | any | Errors + invalid JSON |

Reference: `trading-parser/.../SqInboxPaths.java`, Epic 21 stories 21.1–21.2.

### File structure (NEW)

```
trading-intelligence/
  src/main/java/com/martinfou/trading/intelligence/
    paths/WeeklyBuilderPaths.java
    brief/WeeklyIntelBrief.java
    brief/WeeklyIntelBriefIO.java
    ingest/...
    WeeklyIntelIngestMain.java
data/weekly-intel/.gitkeep
data/weekly-builder/pending/.gitkeep
data/weekly-builder/compiling/.gitkeep
data/weekly-builder/compiled/.gitkeep
data/weekly-builder/deployed/.gitkeep
data/weekly-builder/failed/.gitkeep
data/weekly-builder/dlq/.gitkeep
data/weekly-builder/archive/.gitkeep
```

### Downstream stories

- **22.2** Job 1: ingest + DeepSeek → writes `pending/weekly-plan-*.json`
- **22.4** Job 2: watcher `pending/` → codegen + compile → `compiled/`
- **22.6** Job 3: watcher `compiled/` → PAPER_OANDA → `deployed/`

### References

- [Source: _bmad-output/brainstorming/brainstorming-session-2026-06-01-2324.md] — decisions, schema, schedule
- [Source: _bmad-output/planning-artifacts/epics.md#Epic-22] — epic AC
- [Source: trading-data/.../WeeklyAnalysisRunner.java] — connector usage
- [Source: trading-data/.../ForexFactoryScraper.java] — calendar scrape
- [Source: AGENTS.md] — module dependency policy

### Review Findings

- [x] [Review][Decision] Contradiction logique de sens pour les paires de devises inverses (USD de base) — Un spéculateur COT long JPY s'attend à une hausse du JPY (soit une baisse de USD_JPY). Un particulier OANDA long USD_JPY s'attend à une hausse du USD (baisse du JPY). S'ils sont tous deux longs, ils sont en réalité opposés. Comparer directement les longs sans inversion pour USD_JPY ou USD_CAD produit de faux signaux.
- [x] [Review][Decision] Calcul incorrect de la semaine cible de trading si lancé le lundi — La méthode nextWeekMonday cherche le lundi de la semaine de référence et ajoute 7 jours. Si exécuté un lundi, elle cible la semaine d'après au lieu de la semaine en cours. Quelle règle de ciblage doit s'appliquer en cas d'exécution le lundi ?
- [x] [Review][Patch] Bug d'instrumentation dans la détection de contradiction [ContradictionDetector.java:330-348]
- [x] [Review][Patch] Accumulation de fichiers temporaires .tmp en cas d'échec d'écriture Jackson/déplacement [WeeklyIntelBriefIO.java:255-261]
- [x] [Review][Patch] Omission de dépendance SQLite entraînant un échec de compilation dans le workspace [trading-intelligence/pom.xml]
- [x] [Review][Patch] Risque de crash NullPointerException avec List.copyOf sur des éléments nuls [IngestPipeline.java:511-515]
- [x] [Review][Patch] Risque de NullPointerException si le chemin cible n'a pas de dossier parent [WeeklyIntelBriefIO.java:254]
- [x] [Review][Patch] Risque d'UnsupportedOperationException si le chemin appartient à un système de fichiers virtuel [WeeklyIntelBriefIO.java:256]
- [x] [Review][Patch] Risque de NullPointerException si les listes cot ou oanda passées au détecteur de contradictions sont nulles [ContradictionDetector.java:338]
- [x] [Review][Patch] Risque de NullPointerException si le champ instrument d'une entrée COT est nul [ContradictionDetector.java:344]
- [x] [Review][Patch] Risques d'exception lors du traitement des dates et champs d'événements de calendrier [LiveCalendarIngestStep.java:585]
- [x] [Review][Patch] Risque de NullPointerException si fetcher.fetchAll() retourne null [LiveCotIngestStep.java:625]
- [x] [Review][Patch] Risque de division par zéro ou valeur infinie si le ratio spéculatif COT est de -1 [LiveCotIngestStep.java:628-629]
- [x] [Review][Patch] Risques d'exception lors du traitement des dates et champs d'événements de calendrier [LiveCalendarIngestStep.java:585]
- [x] [Review][Patch] Risques de NullPointerException si les étapes du pipeline d'ingestion retournent des valeurs nulles [IngestPipeline.java:481]
- [x] [Review][Patch] Risque de NullPointerException si brief.generatedAt() est nul [WeeklyIntelIngestMain.java:118]
- [x] [Review][Defer] Notion de Sentiment Retail Oanda limitée au propre compte utilisateur [LiveOandaSentimentIngestStep.java] — deferred, pre-existing
- [x] [Review][Defer] Mode Oanda Practice configuré en dur à true [LiveOandaSentimentIngestStep.java] — deferred, pre-existing

## Dev Agent Record

### Agent Model Used

Composer

### Completion Notes List

- Module `trading-intelligence` added to reactor (deps: core, data only)
- Hot-folder layout under `data/weekly-builder/` + intel under `data/weekly-intel/`
- Ingest pipeline with injectable steps; calendar fail-fast; OANDA missing → PARTIAL
- 10 unit tests green (`mvn test -pl trading-intelligence -am`)

### File List

- `pom.xml` (reactor module entry)
- `trading-intelligence/pom.xml`
- `trading-intelligence/src/main/java/com/martinfou/trading/intelligence/**/*.java`
- `trading-intelligence/src/test/java/**/*.java`
- `data/weekly-intel/.gitkeep`
- `data/weekly-builder/*/.gitkeep`
- `.gitignore`
- `trading-data/.../WeeklyAnalysisRunner.java` (Javadoc cross-link only)

### Change Log

- 2026-06-01: Story 22-1 created — ready-for-dev.
- 2026-06-02: Hot-folder architecture — AC2 WeeklyBuilderPaths, Job 1/2/3 decoupling.
- 2026-06-02: Story 22-1 implemented — ingest + layout; tests pass.
