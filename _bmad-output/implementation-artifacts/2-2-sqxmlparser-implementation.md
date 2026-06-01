# Story 2.2: SqXmlParser Implementation

Status: done

## Story

As a developer,
I want a parser that loads StrategyQuant `StrategyFile` XML into a structured in-memory document,
so that later stories can map rules to Java without re-walking raw DOM.

## Acceptance Criteria

1. **AC1 — Parse API:** `SqXmlParser.parse(Path)` and `parse(InputStream)` return `SqStrategyDocument`.
2. **AC2 — Sections:** Document includes version, engine, `MoneyManagement`, `GlobalSLPT`, `Variables`, and `Rules/Events` with nested `Item`/`Block`/`Param` trees.
3. **AC3 — Item tree:** Nested blocks and formula price params preserve indicator `Item` subtrees (e.g. `EnterAtStop` → HMA).
4. **AC4 — Lookup:** `SqStrategyDocument` supports `variableByName` and `variableById`.
5. **AC5 — Probe reuse:** `SqXmlFormatProbe` delegates to `SqXmlParser`; existing probe tests stay green.
6. **AC6 — Errors:** Missing `StrategyFile`/`Strategy` root throws `SqXmlParseException` with clear message.
7. **AC7 — Build:** `mvn test -pl trading-parser` passes.

## Tasks / Subtasks

- [x] Task 1: Document model (AC: 1, 2, 4)
  - [x] Records: `SqStrategyDocument`, `SqXmlItem`, `SqXmlParam`, `SqXmlRule`, `SqXmlEvent`, money/global SLPT
- [x] Task 2: Parser (AC: 2, 3, 6)
  - [x] `SqXmlDom` shared loader (XXE-safe)
  - [x] `SqXmlParser` walk StrategyFile XML
- [x] Task 3: Integration (AC: 5)
  - [x] Refactor `SqXmlFormatProbe` to use parser output
- [x] Task 4: Tests (AC: 7)
  - [x] `SqXmlParserTest` on community fixture
  - [x] Regression: `SqXmlFormatProbeTest`

## Dev Agent Record

### Agent Model Used

Composer

### Debug Log References

- `mvn test -pl trading-parser` — all tests pass (JForexConverter + sq probe + parser)

### Completion Notes List

- `SqXmlParser` builds `SqStrategyDocument` with events, rules (Signal + IfThen), nested Item/Block/Param trees
- Formula params (EnterAtStop price) preserve indicator subtree (HullMovingAverage)
- `SqXmlFormatProbe` refactored to derive summary from parsed document
- JDK DOM only (no Jackson XML dependency)

### File List

- trading-parser/src/main/java/com/martinfou/trading/parser/sq/SqXmlParser.java (new)
- trading-parser/src/main/java/com/martinfou/trading/parser/sq/SqXmlDom.java (new)
- trading-parser/src/main/java/com/martinfou/trading/parser/sq/SqXmlParseException.java (new)
- trading-parser/src/main/java/com/martinfou/trading/parser/sq/SqStrategyDocument.java (new)
- trading-parser/src/main/java/com/martinfou/trading/parser/sq/SqXmlItem.java (new)
- trading-parser/src/main/java/com/martinfou/trading/parser/sq/SqXmlParam.java (new)
- trading-parser/src/main/java/com/martinfou/trading/parser/sq/SqXmlBlock.java (new)
- trading-parser/src/main/java/com/martinfou/trading/parser/sq/SqXmlSignal.java (new)
- trading-parser/src/main/java/com/martinfou/trading/parser/sq/SqXmlRule.java (new)
- trading-parser/src/main/java/com/martinfou/trading/parser/sq/SqXmlEvent.java (new)
- trading-parser/src/main/java/com/martinfou/trading/parser/sq/SqMoneyManagement.java (new)
- trading-parser/src/main/java/com/martinfou/trading/parser/sq/SqGlobalSlPt.java (new)
- trading-parser/src/main/java/com/martinfou/trading/parser/sq/SqXmlFormatProbe.java (refactored)
- trading-parser/src/test/java/com/martinfou/trading/parser/sq/SqXmlParserTest.java (new)
- docs/sq-xml-format.md

## Change Log

- 2026-05-30: Story 2-2 — SqXmlParser + SqStrategyDocument parse tree

### Review Findings (2026-05-30)

**Verdict:** ✅ AC1–AC7 satisfaits ; probe refactor OK ; `mvn test -pl trading-parser` OK.

#### defer

- [x] [Review][Defer] **`allItems()` sans déduplication** — Peut lister le même sous-arbre plusieurs fois ; suffisant pour 2-2 ; dédupe par `key` en 2-3 si besoin.
- [x] [Review][Defer] **GlobalSLPT non-fixed** — Seuls les values `type=fixed` int sont lus ; autres modes SQ reportés à 0.
- [x] [Review][Defer] **Branches Else / OnInit** — `OnInit` vide et `Else` non modélisés ; OK pour fixture MetaTrader actuelle.
- [x] [Review][Defer] **Jackson XML** — JDK DOM volontaire (aligné 2-1) ; réévaluer en 2-3 si binding POJO simplifie.
