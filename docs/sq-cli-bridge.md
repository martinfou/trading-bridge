# StrategyQuant CLI bridge — TB fitness feedback

Trading Bridge exports backtest scores from inbox `passed/` results into a StrategyQuant external indicator CSV, then re-imports via `sqcli` so Retester can filter candidates (Epic 21, story 21-8).

## Artifacts

| Path | Purpose |
|------|---------|
| `data/sq-cli/fitness/tb-fitness.csv` | SQ external indicator CSV (no header) |
| `data/sq-cli/fitness/tb-fitness-keys.jsonl` | Sidecar mapping CSV rows → strategy manifest IDs |
| `scripts/sq/commands-ext-indicator-setup.txt` | One-shot SQ CLI setup reference |
| `data/sq-cli/scripts/registry.json` | Job `setup-tb-fitness` for indicator registration |

## CSV schema (AC1–AC2)

StrategyQuant expects **no header row**. Each line:

```text
Date,Time,O,H,L,C,V,sharpe,profitFactor,maxDrawdown,compositeScore
```

- **Date** — `dd/MM/yyyy` (UTC)
- **Time** — `HH:mm:ss` (UTC)
- **O,H,L,C,V** — placeholder OHLCV (`1,1,1,1,0`) because SQ requires bar columns before external values
- **sharpe** — annualised Sharpe from TB backtest
- **profitFactor** — gross profit / gross loss
- **maxDrawdown** — peak-to-trough drawdown %
- **compositeScore** — weighted TB validation score (`TbFitnessScoring`)

Strategy keys are **not** embedded in the CSV (SQ has no key column). Use `tb-fitness-keys.jsonl` (tab-separated: `manifestId`, `symbol`, `processedAt`) to correlate rows.

**Path constraint:** import file path must contain **no spaces** (SQ CLI limitation).

## Composite score

```text
composite = sharpe×0.45 + profitFactor×0.25 + returnBoost×0.15 + (1 − min(dd/100, 1))×0.15
```

where `returnBoost = max(totalReturnPct, −100) / 100` and drawdown/Sharpe/profit factor are clamped at zero where noted in `TbFitnessScoring`.

## CLI usage (AC3–AC5)

After inbox processing populates `data/sq-inbox/passed/*-result.json`:

```bash
# Standalone inbox drain + feedback
mvn exec:java -pl trading-parser \
  -Dexec.mainClass=com.martinfou.trading.parser.bridge.SqInboxProcessor \
  -Dexec.args="--sq-feedback"

# Nightly pipeline (includes maintenance jobs + optional export + inbox + feedback)
scripts/sq-nightly.sh --sq-feedback
```

Feedback steps (under `SqJobMutex` unless `--no-lock` / `--dry-run`):

1. Collect fitness metrics from `passed/*-result.json` (requires `compositeScore` field)
2. Write `tb-fitness.csv` and keys manifest
3. Run registry job `setup-tb-fitness` (`-extindicators action=add name=tbFitness values=…`)
4. Import via `-extindicators action=import name=tbFitness file=/absolute/path/tb-fitness.csv`

Dry-run preview:

```bash
mvn exec:java -pl trading-parser \
  -Dexec.mainClass=com.martinfou.trading.parser.bridge.SqJobRunner \
  -Dexec.args="--dry-run --run setup-tb-fitness"
```

## Manual E2E checklist (AC6)

Prerequisites: `SQ_HOME` set, SQ Build 142+ with multi-value external indicators, inbox has at least one passed result with fitness fields.

- [ ] Run inbox on a real SQ export XML; confirm `*-result.json` includes `sharpeRatio`, `profitFactor`, `maxDrawdownPct`, `compositeScore`
- [ ] Run `--sq-feedback` (or nightly with `--sq-feedback`); confirm `data/sq-cli/fitness/tb-fitness.csv` created
- [ ] Confirm `tb-fitness-keys.jsonl` row count matches CSV line count
- [ ] In SQ Retester, verify external indicator `tbFitness` shows four values per bar/timestamp
- [ ] Filter candidates by `compositeScore` threshold and confirm expected strategies remain

Automated E2E: `SqFitnessFeedbackE2ETest` runs when `SQ_HOME` is set (disabled in CI).

## Related

- Epic 21 stories 21-4 (sqcli), 21-5 (job registry), 21-6 (nightly), 21-7 (control plane inbox)
- SQ docs: [Importing multiple external indicator values using CLI](https://strategyquant.com/doc/cli-command-line/importing-multiple-external-indicator-values-using-cli-command-2/)
