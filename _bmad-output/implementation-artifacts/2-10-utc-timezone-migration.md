# Story 2.10: UTC Timezone Migration

Status: review

## Story

As a developer,
I want all timestamps stored and compared in UTC with a documented display timezone,
so that backtest, live OANDA data, and economic calendar events align without silent offset bugs.

## Acceptance Criteria

1. **AC1 — Spec compliance:** Implementation matches [docs/specs.md §2.5](docs/specs.md) (UTC canonical, `America/Toronto` display only).
2. **AC2 — OANDA boundary:** `OandaPriceClient` parses API timestamps to `Instant` (UTC), not naive `LocalDateTime`.
3. **AC3 — Economic calendar:** `EconomicCalendar` events use `Instant` UTC; source publication zones documented in code comments where converted.
4. **AC4 — Domain model:** `Bar`, `Order`, and `Trade` use `Instant` for timestamp fields (or a single `TimeConventions` helper if phased — full migration required for this story).
5. **AC5 — CSV loaders:** `DataLoader` documents assumed CSV timezone; converts to UTC when building `Bar` instances.
6. **AC6 — Live/strategy code:** `AutoTrader`, `StrategyRunner`, `WeekStrategies`, `NewsTradingStrategy` use `Instant` or `Clock` (UTC), not `LocalDateTime.now()`.
7. **AC7 — Build:** `mvn clean install` succeeds; existing tests updated or extended for timezone behavior.

## Tasks / Subtasks

- Task 1: Introduce time utilities (AC: 1, 7)
  - Add `com.martinfou.trading.core.TimeConventions` (or `time` package): `UTC`, `DISPLAY_ZONE = America/Toronto`, `Clock.systemUTC()` factory
  - Helpers: `parseOandaTimestamp(String)`, `toDisplayString(Instant)`
- Task 2: Migrate core domain (AC: 4, 5, 7)
  - `Bar.timestamp()` → `Instant`
  - `Order` createdAt / filledAt → `Instant`
  - `Trade` entryTime / exitTime → `Instant`
  - Update `DataLoader`, `BacktestEngine`, `BacktestResult`, `SmaCrossoverStrategy`, `RunBacktest`
- Task 3: Migrate trading-data (AC: 2, 3, 7)
  - `OandaPriceClient` → `Instant` on candles/prices
  - `EconomicCalendar.Event.time` → `Instant`; convert hardcoded events from documented source zones to UTC
  - `printWeek()` displays in `America/Toronto` with UTC noted in header
- Task 4: Migrate trading-strategies (AC: 6, 7)
  - `NewsTradingStrategy`, `WeekStrategies`, `AutoTrader`, `StrategyRunner`
- Task 5: Docs and agent context (AC: 1)
  - Confirm `docs/specs.md` §2.5 and `project-context.md` match implementation
  - Update `docs/conversion-guide.md` JForex time row if types changed

## Dev Notes

- **Priority:** P0 — do before or in parallel with Story 2.1; news/calendar strategies depend on correct event times.
- **Breaking change:** `Bar`/`Order`/`Trade` API change ripples to all modules; compile-fix entire reactor in one PR.
- **Economic calendar:** Current constants mix local release times (CNY, JPY, GBP, USD). Document each event’s source zone, convert to UTC once at definition or load time.
- **OANDA:** v3 candle `time` field is UTC (RFC3339); preserve offset when parsing.
- **Legacy:** Until migration complete, do not add new `LocalDateTime` in trading code paths.

### Project Structure Notes


| Module               | Files                                                                   |
| -------------------- | ----------------------------------------------------------------------- |
| `trading-core`       | `Bar`, `Order`, `Trade`, `DataLoader`, new `TimeConventions`            |
| `trading-backtest`   | `BacktestEngine`, `BacktestResult`                                      |
| `trading-data`       | `OandaPriceClient`, `EconomicCalendar`                                  |
| `trading-strategies` | `AutoTrader`, `StrategyRunner`, `WeekStrategies`, `NewsTradingStrategy` |
| `trading-examples`   | `RunBacktest`, `SmaCrossoverStrategy`                                   |


### References

- [Source: docs/specs.md §2.5 — Conventions temporelles]
- [Source: _bmad-output/project-context.md — Time rules]
- [Source: docs/conversion-guide.md — Gestion du temps]

## Dev Agent Record

### Agent Model Used

Composer (dev-story workflow)

### Debug Log References

- `mvn clean install` — all modules pass; `TimeConventionsTest`, `EconomicCalendarTest` added

### Completion Notes List

- Introduced `TimeConventions` with UTC storage, Toronto display, OANDA/CSV/event helpers
- Migrated domain models from `LocalDateTime` to `Instant`
- Economic calendar events converted from publication zones (Shanghai, Tokyo, Sydney, London, Toronto, Berlin, New York)
- `WeekStrategies` now references `EconomicCalendar` UTC instants (single source of truth)
- `OandaPriceClient.Price.time` is now `Instant`

### File List

- trading-core/src/main/java/com/martinfou/trading/core/TimeConventions.java (new)
- trading-core/src/test/java/com/martinfou/trading/core/TimeConventionsTest.java (new)
- trading-core/src/main/java/com/martinfou/trading/core/Bar.java
- trading-core/src/main/java/com/martinfou/trading/core/Order.java
- trading-core/src/main/java/com/martinfou/trading/core/Trade.java
- trading-core/src/main/java/com/martinfou/trading/core/DataLoader.java
- trading-backtest/src/main/java/com/martinfou/trading/backtest/BacktestEngine.java
- trading-backtest/src/main/java/com/martinfou/trading/backtest/BacktestResult.java
- trading-data/src/main/java/com/martinfou/trading/data/OandaPriceClient.java
- trading-data/src/main/java/com/martinfou/trading/data/EconomicCalendar.java
- trading-data/src/test/java/com/martinfou/trading/data/EconomicCalendarTest.java (new)
- trading-strategies/src/main/java/com/martinfou/trading/strategies/NewsTradingStrategy.java
- trading-strategies/src/main/java/com/martinfou/trading/strategies/WeekStrategies.java
- trading-strategies/src/main/java/com/martinfou/trading/strategies/AutoTrader.java
- trading-strategies/src/main/java/com/martinfou/trading/strategies/StrategyRunner.java
- trading-examples/src/main/java/com/martinfou/trading/examples/RunBacktest.java
- docs/specs.md

## Change Log

- 2026-05-17: UTC timezone migration — `Instant` domain model, `TimeConventions`, calendar zone conversion, tests
- 2026-05-18: BMad code review — findings in Review Findings below

### Review Findings

#### decision-needed (résolu 2026-05-18)

- [Review][Decision] **Fuseau CSV source** — **1-A** : tous les CSV du projet sont UTC ; garder `csvLocalAsUtc`.
- [Review][Decision] **GBPJPY et Japan GDP** — **2-A** : déclenchement sur UK CPI seul ; corriger le libellé « combined » (→ patch).
- [Review][Decision] **Fenêtre AutoTrader** — **3-A** : fenêtre 1–30 min avant la news ; pas de changement.
- [Review][Decision] **Zone Germany PMI** — **4-C** : vérifier la source plus tard (→ defer).
- [Review][Decision] **AC3 — documentation des zones** — **5-C** : tableau événement → fuseau dans la Javadoc (→ patch).

#### patch

- [Review][Patch] **GBPJPY — libellé UK CPI seul** [`WeekStrategies.java:49`] — Retirer « Japan GDP combined » de la description (décision 2-A).
- [Review][Patch] **Tableau fuseaux AC3** [`EconomicCalendar.java`] — Javadoc : tableau publication zone par événement (décision 5-C).
- [Review][Patch] **parseOandaTimestamp — fractions + offset** [`TimeConventions.java:36-44`] — Les timestamps du type `2026-05-20T14:30:00.123-04:00` ne matchent pas `charAt(19)=='-'` et sont parsés comme UTC naïf (offset perdu).
- [Review][Patch] **Aligner project-context.md** — Le fichier indique encore « Legacy LocalDateTime » sur le domaine ; la migration `Instant` est faite.
- [Review][Patch] **Aligner docs/specs.md §2.5** — Texte « migration progressive » et legacy `LocalDateTime` sur `Bar`/`Order`/`Trade` obsolètes après ce diff.
- [Review][Patch] **Aligner docs/conversion-guide.md** — Note legacy `LocalDateTime` = UTC implicite alors que le guide devrait refléter `Instant`.
- [Review][Patch] **Tests EconomicCalendar** [`EconomicCalendarTest.java`] — Renforcer : filtre HIGH exclut MEDIUM ; assertions UTC golden pour USD/GBP/CAD (pas seulement RBA).
- [Review][Patch] **Test WeekStrategies.newsTime** — Valider que chaque sous-chaîne résout exactement un événement (évite `IllegalStateException` au chargement de classe).
- [Review][Patch] **Test DataLoader → Instant** — Couvrir `csvLocalAsUtc` et lignes CSV invalides/skippées.
- [Review][Patch] **Garde null parseOanda / toDisplayString** [`TimeConventions.java:36-48`] — Éviter NPE si entrée API ou `Instant` null en affichage.
- [Review][Patch] **requireNonNull sur Bar.timestamp** [`Bar.java:30-32`]
- [Review][Patch] **CSV avec suffixe Z ou offset** [`DataLoader.java:25`] — Si une cellule contient `Z` ou `+hh:mm`, router vers `Instant.parse` au lieu de `csvLocalAsUtc`.

#### defer

- [Review][Defer] **Horloge UTC injectable** — `TimeConventions.clock()` est statique ; `Order` / `AutoTrader` non testables avec horloge fictive. [`Order.java`, `AutoTrader.java`] — deferred, hors scope minimal story
- [Review][Defer] **Lignes CSV ignorées silencieusement** — `DataLoader` continue sur parse error (comportement pré-existant). [`DataLoader.java`] — deferred, pre-existing
- [Review][Defer] **Tests d’intégration OandaPriceClient** — `OandaTest` reste smoke ; pas de test unitaire du parsing candle dans le diff. — deferred, nice-to-have
- [Review][Defer] **SmaCrossoverStrategy absent du diff** — Pas de champs temporels ; pas de changement requis pour AC4. — deferred, N/A
- [Review][Defer] **BacktestEngine diff minimal** — Suppression d’import seulement ; compilable avec `Instant` via types domaine. — deferred, verified OK
- [Review][Defer] **Preuve AC7 `mvn clean install`** — Non exécuté dans l’environnement de revue ; à valider localement par le dev. — deferred, environment
- [Review][Defer] **Zone Germany PMI (Berlin vs London)** — Décision 4-C : confirmer sur calendrier source avant changement. [`EconomicCalendar.java:56`] — deferred, verify source