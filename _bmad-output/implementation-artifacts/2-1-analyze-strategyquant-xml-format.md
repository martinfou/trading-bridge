# Story 2.1: Analyze StrategyQuant XML Format

Status: done

## Story

As a developer,
I want a grounded analysis of StrategyQuant X strategy XML and how it relates to our sqimported catalogue,
so that stories 2-2 through 2-9 can implement the parser against real structure instead of the simplified placeholder in `docs/specs.md`.

## Acceptance Criteria

1. **AC1 — Export paths documented:** `docs/sq-xml-format.md` distinguishes `.sqx` (proprietary databank), internal `StrategyFile` XML (exportable via SQ custom analysis), and JForex Java export (existing `JForexConverter` path).
2. **AC2 — Real XML topology:** Document maps `StrategyFile` → `Strategy` → `Variables`, `Rules/Events`, `MoneyManagement`, `GlobalSLPT` with element/attribute notes from a real SQ sample.
3. **AC3 — Building block inventory:** Catalogue families from `docs/sqimported/CATALOGUE.md` mapped to SQ `Item key=` values and Trading Bridge targets (`Indicators`, inline helpers, gaps).
4. **AC4 — Committed fixture:** Real SQ sample XML under `trading-parser/src/test/resources/sq/` (StrategyQuant community sample, attribution in README).
5. **AC5 — Probe utility:** `SqXmlFormatProbe` extracts version, engine, variables, signal UUIDs, building-block keys, entry actions from fixture XML.
6. **AC6 — Spec gap report:** `docs/sq-xml-format.md` §Gap vs `docs/specs.md` §4.1 explains why simplified spec XML is not the on-disk format.
7. **AC7 — Build:** `mvn test -pl trading-parser` passes.

## Tasks / Subtasks

- [x] Task 1: Research & document format (AC: 1, 2, 6)
  - [x] Write `docs/sq-xml-format.md` with export paths, topology, references
  - [x] Add pointer from `docs/specs.md` §4.1 to real format doc
- [x] Task 2: Fixtures & probe (AC: 4, 5)
  - [x] Add SQ sample XML + `sq/README.md` attribution
  - [x] Implement `SqXmlFormatProbe` + report records
  - [x] Unit tests on sample fixture
- [x] Task 3: sqimported crosswalk (AC: 3)
  - [x] Implement `SqImportedBlockInventory` from catalogue families
  - [x] Tests for known families (Vortex, LinReg, Keltner, ADX, ATR)
- [x] Task 4: Validate (AC: 7)
  - [x] `mvn test -pl trading-parser`

## Dev Notes

- **Do not implement full parser** — that is story 2-2 (`SqXmlParser`). This story is analysis + probe only.
- **Parser module:** `trading-parser` depends only on `trading-core`. Use JDK `DocumentBuilder` (no Jackson XML yet).
- **Real sample:** StrategyQuant codebase article exports `Strategy-1.6.221B.txt` (XML). URL: https://strategyquant.com/codebase/reading-strategy-settings-variables-rules-and-building-blocks-in-few-easy-step-using-xml/
- **Existing path:** `JForexConverter` converts exported **Java** (Dukascopy/Oanda template), not XML — document as parallel import path until XML parser lands.
- **UTC:** Strategy XML uses int time codes (e.g. `2100` = 21:00) in `LowestInRange`; map to UTC/session rules in later stories — note in gap section only.
- **FR1 alignment:** Parser epic must eventually support SQ/JForex XML → Java; this story supplies the schema map for `StrategyConfig` POJO (2-3).

### Project Structure Notes

| Path | Purpose |
|------|---------|
| `docs/sq-xml-format.md` | Canonical format analysis (agents + humans) |
| `trading-parser/.../SqXmlFormatProbe.java` | Read-only XML structure extractor |
| `trading-parser/.../SqImportedBlockInventory.java` | Catalogue ↔ SQ block crosswalk |
| `trading-parser/src/test/resources/sq/` | Fixtures |

### References

- [Source: docs/specs.md §4.1 — simplified placeholder XML]
- [Source: docs/sqimported/CATALOGUE.md — GBPJPY families]
- [Source: trading-parser/JForexConverter.java — Java export path]
- [Source: StrategyQuant XML article — Strategy/Variables/Rules structure]
- [Source: _bmad-output/project-context.md — parser in trading-parser only]

## Dev Agent Record

### Agent Model Used

Composer (dev-story workflow)

### Debug Log References

- `mvn test -pl trading-parser` — all tests pass (JForexConverter + new sq probe tests)

### Completion Notes List

- Documented three SQ export paths; real format is `StrategyFile` XML with nested `Item` building blocks
- Committed community sample `strategy-1.6.221B.xml` (~32 KB) with attribution README
- `SqXmlFormatProbe` extracts variables, signals, blocks, entry actions via JDK DOM
- `SqImportedBlockInventory` maps catalogue families A–L to SQ keys; Ichimoku/SuperTrend/SmallestRange flagged GAP
- Updated `docs/specs.md` §4.1 to point at grounded format doc

### File List

- docs/sq-xml-format.md (new)
- docs/specs.md
- trading-parser/src/main/java/com/martinfou/trading/parser/sq/SqXmlFormatProbe.java (new)
- trading-parser/src/main/java/com/martinfou/trading/parser/sq/SqXmlFormatReport.java (new)
- trading-parser/src/main/java/com/martinfou/trading/parser/sq/SqXmlVariable.java (new)
- trading-parser/src/main/java/com/martinfou/trading/parser/sq/SqXmlBuildingBlock.java (new)
- trading-parser/src/main/java/com/martinfou/trading/parser/sq/SqImportedBlockInventory.java (new)
- trading-parser/src/test/java/com/martinfou/trading/parser/sq/SqXmlFormatProbeTest.java (new)
- trading-parser/src/test/java/com/martinfou/trading/parser/sq/SqImportedBlockInventoryTest.java (new)
- trading-parser/src/test/resources/sq/strategy-1.6.221B.xml (new)
- trading-parser/src/test/resources/sq/README.md (new)

## Change Log

- 2026-05-30: Story 2-1 — SQ XML format analysis, probe utility, fixtures, catalogue crosswalk

### Review Findings (2026-05-30)

**Verdict:** ✅ AC1–AC7 satisfaits ; `mvn test -pl trading-parser` OK.

#### patch (applied)

- [x] [Review][Patch] **Tests fixture path** [`SqXmlFormatProbeTest.java`] — Charger via classpath `/sq/strategy-1.6.221B.xml` au lieu d’un chemin relatif `src/test/resources/…`.

#### defer

- [x] [Review][Defer] **`bySqItemKey` first-wins** — Clés dupliquées (ADX, Vortex, LinReg, Keltner) : `putIfAbsent` garde la première famille ; lookup par clé suffit pour 2-1.
- [x] [Review][Defer] **Fixture JForex** — Sample community est `engine=MetaTrader` ; exporter un XML JForex SQ pour 2-2 si parité moteur requise.
- [x] [Review][Defer] **Probe MoneyManagement / GlobalSLPT** — Non extraits par le probe ; documentés dans `sq-xml-format.md` ; parsing 2-2.
