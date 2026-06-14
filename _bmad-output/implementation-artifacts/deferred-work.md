# Deferred Work

Items deferred during code reviews — not blocking current stories.

## Deferred from: code review of 2-4-core-indicators-sma-ema-rsi (2026-05-30)

- **RSI ignore `#ComputedFrom#`** — `SqCoreIndicators.rsi` delegates to close-only `Indicators.rsi` (AC5). Applied-price RSI if needed in 2-5+.
- **Shift négatif → OOB possible** — `SqIndicatorParams.endIndex` not clamped; SQ spinners emit shift ≥ 0; add param validation in 2-6+.
- **`period` ≤ 0 non gardé** — Theoretical divide-by-zero; SQ periods are positive in real fixtures.
- **EMA avec shift non testé** — SMA shift coverage sufficient for 2-4; add EMA shift test in 2-5 if needed.

## Deferred from: code review of 2-5-extended-indicators-macd-bollinger-atr (2026-05-30)

- **MACD signal / histogram not exposed** — `#SignalPeriod#` parsed but only MACD line returned; signal/histogram in 2-6+ if SQ rules need them.
- **No registry alias `BB`** — Catalogue text uses `BB(period,mult)`; register alias if fixture confirms SQ item key.
- **Bollinger upper/lower not exposed** — `BollingerBands` returns middle per AC3; upper/lower via `#Line#` or separate keys in 2-6+.
- **Partial registry dispatch tests** — Only ATR tested via registry; MACD/BB covered in `SqExtendedIndicatorsTest`.
- **Negative shift / period ≤ 0** — Carried from 2-4; param validation in 2-6+.

## Deferred from: code review of 2-6-entry-conditions (2026-05-30)

- **Bar-history / runtime operators** — `IsFalling`, `IsLowerCount`, `MarketPositionIsLong`, etc. in `SqConditionRegistry.DEFERRED`; see `docs/sq-xml-format.md`.
- **Entry rule name heuristics** — `SqEntryEvaluator` uses `rule.name()` contains long/short/entry; tie to signal slot UUIDs if needed in 2-7+.
- **`OR` operator not implemented** — Add when a fixture requires it.
- **Entry without EnterAtStop in Then** — `longEntryActive` reflects If condition only; actions in 2-8.
- **Cross-dependent signals** — No topological sort if signals reference each other.

## Deferred from: code review of 2-7-exit-conditions (2026-05-30)

- **Entry/Exit evaluator duplication** — Same IfThen loop pattern; extract shared helper in 2-8+ if needed.
- **Exit rule name heuristics** — `SqExitEvaluator` uses name/direction like 2-6 entry; UUID-based routing optional later.
- **`CloseAllPositions` execution** — Detection only; MARKET close orders in 2-8.
- **Manual position state** — `PositionState` injected by caller; backtest/broker sync in 2-8/2-9.
- **No positive shortExit test** — Fixture ShortExit uses deferred `IsLowerCount`; shape tests sufficient for 2-7.

## Deferred from: story 2-8-position-sizing (2026-05-30)

- **GAP stop-price indicators** — Fixture Short entry `#Price#` uses `HullMovingAverage`; `stopPrice` empty until registry extended.
- **Fixture end-to-end entry** — Short entry IfThen includes deferred `IsFalling`; synthetic doc tests cover orchestration.
- **`SqOrderIntent.toOrder()`** — Intent model ready; backtest wiring in 2-9.
- **Magic number resolution** — `SqCloseIntent.magicNumberVariable()` stores ref; runtime lookup in 2-9.

## Deferred from: code review of 2-8-position-sizing (2026-05-30)

- **`#Size#` variable ref uses `intParameter`** — fractional lots (e.g. 0.1) truncated; fixture uses `UseGlobalMM` → `fixedSizeOr`.
- **Evaluator duplication** — `SqStrategyActionsEvaluator` inline IfThen loop vs `SqEntryEvaluator`/`SqExitEvaluator`; extract shared helper if 2-9 needs both.
- **`toOrder()` partial** — SL/PT pips and `barsValid` not mapped to `Order`; 2-9 backtest integration.
- **EnterAtStop magic number** — `#MagicNumber#` param not on `SqOrderIntent`.
- **Duplicate test support class** — `StrategyConfigTestSupport` in `actions` and `conditions` packages.

## Deferred from: story 2-9-java-code-generator (2026-05-30)

- **Catalog registration** — Generated classes not wired into `StrategyCatalog` / `SqImportedStrategyCatalog`; use `SqInterpretedStrategy.fromClasspath` or write + register manually.
- **Full inlined codegen** — Generator emits interpreter wrappers, not standalone indicator/condition Java like `JForexConverter`.
- **Position sync with backtest fills** — Interpreter tracks intent state; engine fill events not yet fed back.
- **Embedded XML in generated source** — Generated classes reference classpath XML resource; inline XML constant optional later.

## Deferred from: code review of 2-9-java-code-generator (2026-05-30)

- **trading-parser runtime dep for generated classes** — Wrappers import `SqInterpretedStrategy`; output module must depend on `trading-parser`.
- **`RunBacktest` / catalog wiring** — No auto-registration in `StrategyCatalog`; manual or follow-up story.
- **Optimistic position state** — Open flag set when order queued, not when engine fills STOP.
- **`barsValid` / magic filter** — Intent fields parsed but not enforced in interpreter loop.
- **No javac compile test** — Generator output validated by string asserts only; add compile smoke test like `JForexConverter`.

## Deferred from: code review of 21-1-hot-folder-strategy-manifest (2026-05-31)

- **Concurrent `ensureSidecar`** — Two workers can race on manifest creation; add locking in 21.2 processor.
- **XML size limit / OOM** — `readAllBytes` unbounded; cap in inbox processor 21.2.
- **Path confinement to inbox** — No repo-root check yet; 21.2 CLI should validate paths under `data/sq-inbox/`.
- **`exportedAt` from file mtime** — Not SQ export timestamp; acceptable for 21.1 traceability stub.

## Deferred from: code review of 21-2-sq-inbox-processor (2026-05-31)

- **PASS = no exception only** — No minimum trades or return threshold; add gates in 21.3+ or promote flow.

## Deferred from: code review of 21-3-xml-validation-dlq-coverage (2026-05-31)

- **UNKNOWN indicators pass to backtest** — Listed in coverage report only; DLQ only for inventory GAP and bad entry actions (by design).
- **No CLI `--max-xml-bytes`** — Limit configurable via `SqInboxOptions` constructor only.
- **INLINE inventory vs runtime** — LowestInRange etc. marked inline but not in `SqIndicatorRegistry`; interpreter gap unchanged.

## Deferred from: code review of 21-4-sqcli-runner-mac (2026-05-31)

- **SQ_HOME symlink to .app** — User staging via `contributing.md`; no auto-discovery of Mac app bundle internals.
- **Dry-run requires valid SQ_HOME dir** — Use `--sq-home` or env even for dry-run; no placeholder-only mode.

## Deferred from: code review of 21-5-sq-job-mutex-script-registry (2026-05-31)

- **Shared resolveRepoRoot** — Duplicated in `SqJobRunner` and `SqInboxProcessor`; extract in 21-6 pipeline story.
- **`--list` without args** — Job listing omits sqcli args; add if operators need copy-paste from CLI.

## Deferred from: code review of 21-6-nightly-pipeline-sqcli-inbox (2026-05-31)

- **Shallow export dir** — `importExports` lists top-level `*.xml` only; recursive glob if SQ exports to subdirs.
- **Manifest sidecar not copied** — only `.xml` from `SQ_EXPORT_DIR`; manifest regenerated on inbox processing.
- **Job failure does not short-circuit** — inbox runs even when `update-data` fails; consider fail-fast in 21-7 runtime hooks.

## Deferred from: code review of 21-7-runtime-sq-bridge-hooks (2026-05-31)

- **Probe blocks HTTP thread** — cache miss runs sqcli synchronously in `GET /api/sq-bridge/status` (up to 15s); background probe in 21-8+.
- **sq-bridge events off run lifecycle** — `SQ_EXPORT_RECEIVED` appended to run id `sq-bridge` without `RunRecord`; `/api/runs/sq-bridge/events` returns 404.
- **Mutex busy after 202** — POST accepted then worker fails if external sqcli holds lock; surface in status only via `lastInboxRun.error`.

## Deferred from: code review of 21-8-fitness-external-indicators-loop (2026-05-31)

- **Control plane `process-inbox` has no `--sq-feedback`** — `SqBridgeService` drains inbox only; fitness loop remains CLI/nightly per AC3 scope.

## Deferred from: code review of 22-1-weekly-intel-brief-ingest.md (2026-06-06)

- **Notion de Sentiment Retail Oanda limitée au propre compte utilisateur** — L'analyseur appelle `/openTrades` de l'account ID configuré, ce qui correspond aux transactions de l'utilisateur lui-même et non au sentiment retail global.
- **Mode Oanda Practice configuré en dur à true** — Pas de paramétrage externe pour basculer en mode de production réel.

## Deferred from: code review of 25-3-implementation-des-outils-d-ingestion-avec-isolation-temporelle (2026-06-14)

- **Perte des métadonnées macroéconomiques previous et forecast** — Le DTO `WeeklyIntelBrief` ne stockant pas les valeurs `previous` et `forecast` pour les événements du calendrier, celles-ci sont retournées sous forme de chaînes vides `""`, privant le modèle de contexte historique.

