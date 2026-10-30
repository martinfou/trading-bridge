# PRD — Futures Trading Engine (backtest + live through trading-bridge and IBKR)

> **Phase**: 2 (Planning) · **Status**: DRAFT v1, Party Mode round integrated
> **Product Owner**: Martin Fournier · **Author**: Hermes, 2026-10-29
> **Language note**: this PRD is in English because its consumers are the coding agents implementing it and the sibling tech-spec `tech-spec-ibkr-live-futures-lane.md`. Conversation and decisions stay in French.
> **Relationship to prior artifacts**: see §10. This PRD **replaces** `prds/prd-ibkr-futures-2026-06-15/` as the authority for futures execution. It does **not** replace Epic 45 (futures backtesting engine, delivered) and it **extends** Epic 46 rather than re-scoping it.

---

## 0. Document purpose

Martin wants **one trading engine** with which he can backtest a futures strategy and then trade that same strategy through trading-bridge against IBKR, without writing a new executable per idea.

This document specifies that engine. It states what already exists (§3), what is actually missing (§4), what is in and out of scope (§5, §6), and how success is measured in a way that cannot be faked (§7).

---

## 1. Vision

Today the platform can backtest a futures idea and can run a live strategy, but the two are not the same system:

- **Backtesting** is 75 hand-written `main()` classes (`trading-examples/.../Run*.java`), each with its own hardcoded initial capital, commission and slippage. Adding an idea means writing another executable.
- **Live trading** is one runner (`LiveStrategyRunner`) wired to OANDA, where "adding a strategy" means an entry in `getStrategyMap()`, an env var and a container.
- **IBKR**, the only venue that offers the futures Martin actually wants to trade, has a complete set of components and no process that runs any of them.

The engine collapses those three facts into one: **a strategy is described once (config), it names its instrument, and the same description runs in backtest mode or paper/live mode against a broker.**

The value is not a prettier backtest. It is that **the strategy that was validated is literally the strategy that trades**, through the same cost and fill instrumentation, so the backtest/live gap becomes a measured number instead of a hope.

---

## 2. Target user

### 2.1 Primary persona

**Martin**, independent quantitative trader. He designs strategies (often imported from StrategyQuant X), wants to validate them on futures with realistic costs and rollover, and wants the validated ones to trade his IBKR paper account unattended. He is the only operator; there is no team, no multi-tenant requirement, no compliance surface.

### 2.2 Jobs to be done

- **"Let me test an idea in minutes, not in a new class."** Describe a strategy run in a config file, run it against local bars, get a report.
- **"Let me trade the exact thing I tested."** The same strategy identity, instrument and size, now against IBKR.
- **"Let me see the gap."** Per-trade slippage and commission from both paths, comparable, so a backtest that does not predict reality is visible.
- **"Let me not lose money to an unguarded position."** Every live entry carries a protective stop at the venue.

### 2.3 Key user journeys

**UJ-1 — Backtest a futures strategy from config**
Martin writes or reuses a config naming `symbol: MGC`, `timeframe: M30`, the strategy name, and a date range. He runs one command. The engine loads bars (`.bars` or CSV through `HistoricalDataLoader`), drives `BacktestEngine` with rollover, margin tracking, commission and slippage from the config, and writes a report. **No code is written.**

**UJ-2 — Run the same strategy on IBKR paper**
He changes `mode: paper` and the broker block. The same strategy runs against the IBKR gateway on port 4002, at the same timeframe, sized the same way. Every entry is sent with a protective stop; if the stop is rejected, the position is flattened and the lane halts.

**UJ-3 — Compare the two**
He reads one fill log covering both runs, keyed by the same strategy and instrument, and sees the median slippage and commission delta. If the backtest's assumption is out of line with reality, it is a number he can act on, not an opinion.

---

## 3. What already exists (measured 2026-10-29)

Stated here so that nobody — human or agent — rebuilds it. Each line was verified on the repository, not recalled.

| Capability | Where | State |
|---|---|---|
| Backtest engine with **rollover, margin tracking, fill mode, fixed/pct commission, fixed/pct slippage, stop slippage, risk-free rate** | `trading-backtest/src/main/java/com/martinfou/trading/backtest/BacktestEngine.java` | Exists, tested, used |
| Bar loading with two formats (Dukascopy CSV preferred, `.bars` fallback) | `trading-data/.../HistoricalDataLoader.java` | Exists; `data/historical/csv/MGC_M30_2026.csv` present, `.bars` 2003-2026 |
| Futures data on disk | `data/historical/bars/` | MGC M30 (24 files, 2003-2026), MES H1 (15), MNQ M15 (15), M2K H1 (21) |
| A working futures backtest | `trading-examples/.../RunMgcGoldBreakoutBacktest.java` | Runs MGC M30 with COMEX costs ($0.62/contract, 1 tick slippage) |
| IBKR broker + gateway client + contract resolver + bracket builder + reconciler + supervisor | `trading-broker/.../Ibkr*.java`, `trading-data/.../ibkr/` | Exist, tested, **zero production caller** |
| IBKR paper gateway | separate compose project `~/ibkr-gateway`, port 4002 | Running, **zero consumer** |
| Live strategy runtime (OANDA) | `trading-strategies/.../LiveStrategyRunner.java` | Running: 4 GBP_JPY strategies on account `-014` |
| Strategy registry | `getStrategyMap()` in `LiveStrategyRunner` | 32 strategies registered |
| Two MGC strategies, configured at 0.5 % risk each | `config/live-config.json`, `strategies/sqimported/` | Registered, **never executed** |

**The one thing that does not exist:** a process that runs an IBKR strategy, and a way to place a protective stop on IBKR (§4.2).

---

## 4. Problem statement

### 4.1 There is no engine, there are 75 executables

`ls trading-examples/src/main/java/com/martinfou/trading/examples/Run*.java | wc -l` returns **75**. Each embeds its own capital, commission, slippage and symbol. Consequences:

- Two backtests of the "same" strategy can disagree because their constants differ.
- Nothing enforces that a live strategy was backtested with the costs it will actually pay.
- The cost of trying an idea is a code change, so ideas are tried less.

### 4.2 The IBKR execution path cannot protect a position

`IbkrBroker.submitOrder` accepts `MARKET` **only** and **silently discards `order.stopLoss()` and `order.takeProfit()`** (`IbkrBroker.java:134`). `IbkrGatewayClient` exposes a single placement method, `placeMarketOrder`. `IbkrBracketOrderRouter` builds `STOP`/`LIMIT` orders that no code path can send.

Therefore **any entry through today's IBKR stack is a naked position**, and every risk statement about the futures lane is unenforceable. This is a prerequisite for the engine, not a follow-up epic.

### 4.3 Backtest and live do not share instrumentation

The backtest applies slippage and commission; the live path applies whatever the venue does. Nothing emits both in a comparable form, so "is the backtest predicting the live?" has no answer today and cannot be answered by reading either side alone.

---

## 5. Features (in scope)

Each feature has an ID, a requirement, and an acceptance criterion that can be checked by a command or a test. **No feature is "done" without its criterion.**

### F1 — Config-driven run (the engine's entry point)

A single executable reads one run description and executes it. Minimum fields: `strategy`, `symbol`, `timeframe`, `mode` (`backtest` | `paper` | `live`), `broker` (`oanda` | `ibkr`), `period` (for backtest), `risk` (percent or fixed size), `capital`, and a `costs` block (commission, slippage).

- **One strategy per process.** The engine is parameterized, not a multi-strategy orchestrator. This preserves the existing "one container per strategy" contract that the deployed lane depends on.
- Unknown strategy, unknown symbol, unknown mode ⇒ refuse to start, naming the valid values.
- `mode: live` with a broker whose account is not explicitly marked live-capable ⇒ refuse.

**Acceptance:** running the engine with a config for `MGC`/`M30`/`backtest` produces a report without editing any Java file; running it with a deliberately invalid `mode` exits non-zero and prints the accepted values.

### F2 — Backtest mode reuses `BacktestEngine` unchanged

The engine wires `BacktestEngine`; it does not reimplement fills, costs, margin or rollover. All cost parameters come from the config, never from constants in a `main()`.

**Acceptance:** a backtest of `MGC` M30 run through the engine produces the same trade count and final equity (within a documented tolerance) as the equivalent `RunMgcGoldBreakoutBacktest` run when given the same inputs and costs. Divergence is either zero or explained in the report.

### F3 — IBKR paper/live mode, with a protective stop that actually exists

The engine can run the same strategy against IBKR. Requirements:

- `IbkrBroker.submitOrder` routes by order type, so a `STOP` is sent as a stop, not silently dropped.
- Every entry carries a protective stop at the venue. If the stop is not accepted, the position is flattened and the lane halts with an ERROR.
- Reuse `IbkrBracketOrderRouter` for bracket construction and `IbkrStateReconciler` for post-restart reconciliation.
- Paper port only unless a live account is explicitly enabled; refuse `4001`, `7496`, `7497`.
- `OrderTripwire` applies to every order path, including the new stop path.

**Acceptance:** on paper, one entry fills and a protective stop is visible at the venue before the next bar; a deliberately stopless entry is refused locally; an accepted-stop-then-rejected-stop scenario flattens and halts.

### F4 — A single fill log, identical in both modes

One structured record per fill, emitted in backtest and in live, with the same field names: strategy, symbol, side, quantity, requested price, filled price, computed slippage, commission, timestamp, and the run mode. This is what makes UJ-3 possible.

**Acceptance:** a backtest run and a paper run of the same strategy both produce records with the identical schema; a report command prints median slippage and commission per mode side by side.

### F5 — Refuse to run a strategy whose costs were never validated

A strategy may run in `paper`/`live` only if its config names the backtest that validated it (a run id or a report path) **or** explicitly sets `unvalidated: true`. Unvalidated runs are allowed on paper, refused on live.

**Acceptance:** a live run of an unvalidated strategy exits non-zero with a message naming what is missing.

---

## 6. Non-goals

- **Rebuilding backtesting.** Epic 45 delivered it; `BacktestEngine` is reused as-is.
- **An IHM, dashboard page or promotion UI.** `desktop/` and `dashboard/` exist; neither is in this scope. The interface is a config file plus a CLI.
- **A strategy registry with a database, a state machine for promotion, or multi-account routing.** Promotion is manual: a config change.
- **A strategy DSL or a rule engine shared by backtest and live.** The two have different latency and fill assumptions; sharing an evaluator would encode the wrong ones.
- **Migrating the OANDA lane.** It keeps running untouched.
- **Automatic quarterly rollover** in the live path (backtest rollover is already supported and is in scope through F2).
- **MCL, or any symbol without local bars.**
- **Live money.** Not in this PRD, at all.

---

## 7. Success metrics

Measurable within 30 days of F1–F5 landing:

| Metric | Target | Why this and not something else |
|---|---|---|
| New backtest executed without touching Java | ≥ 3 distinct configs run | Directly measures F1; a hand-written class would fail this |
| IBKR paper entries with a venue-visible stop | 100 % of entries, 0 naked positions | The only non-negotiable risk property (§4.2) |
| Fill-log coverage | 100 % of fills in both modes carry the full schema | Makes the gap measurable; partial coverage makes the comparison a lie |
| Median slippage delta (live minus backtest) reported | A number, with sample size | Vague "robustness" scores are decoration; this is falsifiable |
| Backtest parity (F2) | Same trade count and equity within tolerance | Proves the engine reuses the engine instead of approximating it |

**Explicitly not a success metric:** any isolated backtest score (Sharpe, PF, win rate). A backtest that never meets the live path proves nothing, and this PRD exists because that gap was invisible.

---

## 8. Open questions (defaults pre-answered; Martin may override)

| # | Question | Default | Why |
|---|---|---|---|
| Q1 | Does the engine replace the 75 `Run*.java`, or coexist? | **Coexist during migration**: new work uses config, existing mains stay until their config equivalent is proven by F2 parity, then are deleted one by one | Deleting 75 runnables in one pass loses the only record of how each was configured |
| Q2 | Signal contract: reuse `Strategy` (returns `Order` with `stopLoss`/`takeProfit`) or introduce a `Signal` type? | **Reuse `Strategy` and `Order`** | `Order` already carries side, quantity, price, stopLoss, takeProfit, ocaGroup, parentId. A new type would force a rewrite of 32 strategies for no capability gain |
| Q3 | Where does the config live, and in what format? | **`config/runs/<strategy>-<symbol>-<mode>.json`**, JSON, validated at startup | JSON is already the project's config language (`live-config.json`); no new dependency |
| Q4 | Does the engine own the container, or does compose? | **Compose one container per config**, as today | Preserves the deployed lane's isolation and the reconciler's assumptions |
| Q5 | Is the IBKR stop work (F3) inside this PRD or a prerequisite epic? | **Inside, and first** | John's framing error #3: a stop is not an annex when its absence makes every live position naked |

Decisions accepted by Martin are recorded in `.decision-log.md` in this folder, continuing the `D<n>` series.

---

## 9. Assumptions index (verification status)

Mary's contribution, kept as a table so no requirement silently rests on an unproven claim.

| # | Assumption | Status | How it was / will be checked |
|---|---|---|---|
| A1 | Local MGC M30 bars are complete for the intended period | **Verified (2026-10-29)** | Measured by `FuturesTradingEngineTest#a1AndA2_measureHistoricalBarsCompletenessAndCsvVsBarsAgreement`: all 24 annual files (`2003`–`2026`) exist and are non-empty, totaling **299,396** M30 bars across `[2003-05-05T03:00:00Z .. 2026-10-23T23:30:00Z]` with only 20 multi-day holiday gaps > 72h over 24 years |
| A2 | The CSV and `.bars` sources agree for the same period | **Measured — Divergent by session filter (2026-10-29)** | Over the exact 2026 CSV window (`2025-10-24T13:30:00Z .. 2026-10-23T20:30:00Z`), `MGC_M30_2026.csv` contains **3,761** RTH-only bars (`09:30–16:00 ET`), yielding **27 trades** and **\$16,052.26** final equity on `sq-gold-m30`; `MGC_M30_2025..2026.bars` contains **11,784** full 23h/day Globex bars over the same window, yielding **61 trades** and **\$15,772.18** final equity. Per Decision `D11`, `.bars` (2003–2026) is authoritative and the CSV vs `.bars` divergence is explicitly documented |
| A3 | Backtest costs approximate real IBKR costs | **Instrumented (F4 ready, awaiting 20 paper fills)** | Unified JSONL fill logger (`FillLogWriter`) + `FuturesTradingEngine --report` compare median slippage and commission side-by-side once ≥ 20 paper fills accumulate on port 4002 |
| A4 | `IbkrBroker` cannot place a stop | **Verified & Fixed (Story 46-8)** | `IbkrBroker.submitOrder` now routes `MARKET`, `STOP`, and `LIMIT` to `IbkrGatewayClient.placeStopOrder`/`placeLimitOrder` under `OrderTripwire` |
| A5 | The two MGC strategies are ready for live | **Verified on Backtest, Gated by F5** | Validated on 2025–2026 `.bars` via `config/runs/sq-mgc-m30-breakout-0-24542-MGC-backtest.json` and `sq-mgc-m30-breakout-0-32921-MGC-backtest.json`; `FuturesTradingEngine.validateConfig` enforces the F5 `validatedBy` gate before paper/live |
| A6 | "Epic 46 is complete" | **False (Fixed by Stories 46-7..46-12)** | `IbkrLiveRunner`, `SqStrategyEvaluator`, `IbkrOrderDispatcher`, `IbkrBarSource`, and `docker/docker-compose.futures.yml` are now delivered and status records corrected in 46-12 |
| A7 | The prior PRD's "finalized" status means delivered | **False** | Status in a document is not evidence of execution |

**Rule for implementers:** a feature may not claim done while the assumption it depends on is A1-A3 unverified. Those three are measured, not argued.

---

## 10. Relationship to prior artifacts

| Artifact | Relationship |
|---|---|
| `prds/prd-ibkr-futures-2026-06-15/` (Epics 29-30, MES + desktop) | **Superseded as the authority for futures execution.** Its broker lessons are absorbed. Its desktop-UI scope and MES-only focus are dropped. Its "finalized" label never meant delivered. |
| `epics-and-stories-epic-45.md` (futures backtesting engine) | **Delivered and reused.** Not re-scoped. |
| `epics-and-stories-epic-46.md` (IBKR live autopilot) | **Extended, not re-scoped.** The chain 46-7..46-12 in the sibling tech-spec delivers the runner; this PRD supplies the product frame it sits in. |
| `tech-spec-ibkr-live-futures-lane.md` | The implementation brief for F3. Read it before writing IBKR code. |

---

## 11. Party Mode record (2026-10-29)

Three agents reviewed the framing before this PRD was written. Their takes, then what was adopted or rejected.

### John (PM) — verbatim

> **1. Périmètre.** L'interprétation la plus rentable : un **runner unique piloté par config** (fichier YAML/JSON) qui réutilise `BacktestEngine` en backtest et `IbkrBroker` en paper/live, derrière une même interface. **Indispensable** : (a) exécution config-driven remplaçant les dizaines de `main()` ; (b) un chemin IBKR réellement exécuté ; (c) corriger `IbkrBroker.submitOrder` pour supporter stopLoss/takeProfit — « sans ça, pas de stop protecteur, donc pas de live sérieux ». **Gold-plating** : le cycle de vie complet « backtest → promotion → paper → live », un registre avec UI, le multi-compte, refaire une IHM.
>
> **2. Ancien PRD : remplacer, pas absorber.** Centré MES + IHM Electron, marqué finalisé, live jamais livré : il est périmé. On absorbe les leçons du broker IBKR, on enterre le reste.
>
> **3. Trois erreurs de cadrage :** re-re-faire le backtesting alors qu'Epic 45 est à 100 % ; construire une IHM ; ignorer le blocage stop IBKR.
>
> **MVP minimal :** runner config-driven (mode backtest/paper), `IbkrBroker` corrigé (stop loss/take profit, erreur explicite au lieu du drop silencieux), gateway paper 4002 branché, 1 stratégie de bout en bout (MGC breakout, backtest → paper). Pas d'IHM, pas de pipeline de promotion, pas de migration OANDA.

### Mary (Analyst) — verbatim

> **1. Hypothèses non vérifiées :** le CSV MGC est présent et complet ; `.bars` et CSV sont identiques ; le fill mode/slippage/commission du backtest égale les coûts IBKR réels ; `IbkrBroker` peut exécuter une stratégie avec stop ; les stratégies ELITE sont prêtes pour le live.
>
> **2. Écarts backtest ↔ live :** remplissage (slippage paramétrable vs MARKET réel) ; gestion des stops (le backtest simule, `IbkrBroker` ne sait pas placer) ; données (pas de H1 MGC, pas de MCL) ; frais (0.62/0.10 en dur vs frais réels inconnus). **Exigence :** un logger de remplissage unique, backtest ET live, mêmes champs (prix, slippage, commission, latence) + un test d'équivalence sur fenêtre commune.
>
> **3. Indicateur 30 jours :** écart médian slippage backtest vs live sur ≥ 50 trades réels, et exécution des stops confirmée par les logs. **Décoration :** « le moteur est robuste » ou tout score de backtest isolé.
>
> **4. Conclusions non prouvées :** « les briques de réalisme existent » est vrai en API mais leur calibrage n'est pas démontré ; le statut « finalisé » du PRD antérieur n'a aucune valeur probante.

### Winston (Architect) — verbatim

> **1. L'abstraction juste.** Le contrat de stratégie est le seul invariant à partager : une stratégie produit des **signaux** (instrument, sens, taille, stop, take-profit), pas des ordres. Un `Broker` interchangeable (`submit` / `getPositions` / `cancel`). Un registre de stratégies + une config déclarative remplaçant les `main()` en dur. **Sur-ingénierie :** bus d'événements/CEP, moteur de règles partagé backtest/live (hypothèses de latence et de fill trop différentes), abstraction multi-venue générique, DSL de stratégies. « Ne généralisez qu'au deuxième consommateur réel. »
>
> **2. Contrat à ne pas casser.** Un conteneur par stratégie (le moteur reste un exécutable monoposition) ; `OrderTripwire` reste au-dessus du moteur ; plafond de risque validé au démarrage ; état par stratégie persistant et réconciliable.
>
> **3. Pièces IBKR, dans l'ordre.** D'abord `IbkrBroker.submitOrder` acceptant stop/takeProfit + un `placeBracketOrder` dans le client. Puis un `IbkrLiveRunner` minimal : signal → bracket → `IbkrStateReconciler`. Laisser au sprint suivant : `CmeAutoRolloverManager`, `BarCloseScheduler`, `IbkrOrderExecutionManager`, supervision gateway.
>
> **Ce que je ferais :** livrer d'abord le bracket order IBKR + un runner minimal branché sur le contrat de signal existant, sans toucher au modèle un-conteneur-par-stratégie ni à l'`OrderTripwire`, puis unifier backtest et live sur ce même contrat via un registre + config, en reportant rollover et scheduler.

### Orchestrator note — what changed

- **Adopted from John:** the engine is a config-driven runner, not a lifecycle platform. The old PRD is superseded (§10). The stop is a precondition, not an annex (Q5).
- **Adopted from Mary:** the assumptions table (§9) with verification status, the single fill log (F4), the parity test (F2 acceptance), and "no backtest score as a success metric" (§7). Her A6/A7 findings are stated as fact, not as risk.
- **Adopted from Winston:** one container per strategy (F1), `OrderTripwire` above the engine, the over-engineering blacklist (§6), and IBKR ordering: bracket before scheduler before rollover (F3, and the tech-spec's 46-8 before 46-10).
- **Rejected from Winston:** a new `Signal` type. `Order` already carries side, quantity, price, `stopLoss`, `takeProfit`, `ocaGroup` and `parentId`; introducing `Signal` would force a rewrite of 32 strategies for zero new capability. Recorded as Q2. His intent (strategies emit intent, not venue calls) is preserved because `Order` is a value object and only the broker executes it.
- **Rejected from John:** replacing all 75 `Run*.java` immediately. Coexistence with parity-gated deletion (Q1) is safer and gives F2 something to prove against.
- **Where the two genuinely disagreed:** John wanted the config runner to absorb the per-strategy executables now; Winston warned that generalizing early is the trap. Resolution: the engine is config-driven from day one, but its *coverage* grows one parity-proven strategy at a time.
