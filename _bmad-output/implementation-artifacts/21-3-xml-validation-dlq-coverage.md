# Story 21.3 — Validation XML, DLQ et couverture interpréteur

Status: done

Epic: 21 — Pont StrategyQuant CLI

## Story

As Martin,
I want SQ XML drops validated for size, path safety, and interpreter coverage before backtest,
so that unsupported strategies land in `dlq/` with a coverage report instead of opaque backtest failures.

## Acceptance Criteria

- [x] **AC1** — Pre-parse validation : taille max XML, chemin confiné sous `pending/`
- [x] **AC2** — `SqXmlCoverageValidator` : inventaire blocs (registry, deferred, INLINE inventory, GAP)
- [x] **AC3** — GAP inventory ou action d’entrée non supportée → `dlq/` + `*-coverage.json`
- [x] **AC4** — Erreur parse / backtest → `failed/` ; succès → `passed/` (inchangé)
- [x] **AC5** — `*-coverage.json` écrit pour tout traitement (passed/failed/dlq)
- [x] **AC6** — Tests : GAP → dlq, fixture catalogue → passed avec rapport coverage
- [x] **AC7** — `docs/contributing.md` : règles failed vs dlq

## Tasks

- [x] `SqInboxValidation` + limites dans `SqInboxOptions`
- [x] `SqXmlCoverageValidator` + `SqCoverageIO`
- [x] Intégrer dans `SqInboxProcessor`
- [x] Tests + docs

## Dev Notes

- UNKNOWN indicators (ex. HMA) → rapport seulement ; DLQ sur `SqImportedBlockInventory` GAP ou `EnterAt*` ≠ EnterAtStop
- Deferred operators (IsFalling) → listés, n’empêchent pas le backtest
- FR-SQ1

## File List

- `trading-parser/.../bridge/SqInboxValidation.java`
- `trading-parser/.../bridge/SqXmlCoverageValidator.java`
- `trading-parser/.../bridge/SqXmlCoverageReport.java`
- `trading-parser/.../bridge/SqCoverageIO.java`
- `trading-parser/.../bridge/SqInboxProcessor.java` (dlq routing)
- `trading-parser/.../bridge/SqInboxOptions.java` (maxXmlBytes)
- `trading-parser/.../bridge/SqInboxResult.java` (disposition)
- `trading-parser/.../conditions/SqConditionRegistry.java` (public coverage API)
- `trading-parser/src/test/resources/sq/strategy-gap-ichimoku.xml`
- `trading-parser/src/test/.../SqXmlCoverageValidatorTest.java`
- `trading-parser/src/test/.../SqInboxProcessorTest.java`
- `docs/contributing.md`

## Change Log

- 2026-05-31: Story 21-3 — validation, coverage report, DLQ routing.
- 2026-05-31: CR patches — path confinement, InboxValidationException, tests, logging.
- 2026-05-31: Revue CR patches appliqués ; statut → done.

## References

- Story 21-2 `SqInboxProcessor`
- `SqImportedBlockInventory`, `SqIndicatorRegistry`, `SqConditionRegistry`

### Review Findings (2026-05-31)

- [x] [Review][Patch] Confinement chemin — `relativize` + rejet `..` [`SqInboxValidation.java`]
- [x] [Review][Patch] `InboxValidationException` — routage DLQ typé [`SqInboxProcessor.java`]
- [x] [Review][Patch] Javadoc / log — `passed` vs `dlq/failed` [`SqInboxProcessor.java`]
- [x] [Review][Patch] Test — `broken-coverage.json` sur `failed/` [`SqInboxProcessorTest.java`]
- [x] [Review][Patch] Test — `SqInboxValidationTest` sibling `pending_other` [`SqInboxValidationTest.java`]
- [x] [Review][Defer] UNKNOWN (ex. HMA) → `passed` avec warning coverage — voulu (Dev Notes story)
- [x] [Review][Defer] Pas de CLI `--max-xml-bytes` — `maxXmlBytes` programmatique seulement
- [x] [Review][Defer] INLINE inventory ≠ exécution registry — comportement interpréteur inchangé
- [x] [Review][Dismiss] `SqConditionRegistry` rendu public — acceptable pour couverture inbox
