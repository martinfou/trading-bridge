---
name: bmad-strategy-backtest-review
description: 'Reviews trading strategy backtest metrics, parameter optimization results, risk metrics (Sharpe, Drawdown, Profit Factor), and Walk-Forward Analysis (WFA) stability. Use when evaluating strategy performance or preparing a strategy for deployment.'
---

# Trading Strategy & Backtest Review Workflow

## 1. Purpose & Desired Outcomes

Provide an automated, evidence-graded performance review of a quantitative trading strategy's backtest and optimization results.

When this workflow completes, the agent will have produced:
1. **Strategy Review Artifact**: Saved to `{project-root}/_bmad-output/implementation-artifacts/strategy-review-<strategy-name>.md`.
2. **Key Metric Summary**: Sharpe Ratio, Sortino Ratio, Max Drawdown %, Profit Factor, Win Rate, Expectancy, and Trade Count.
3. **Overfitting & Sensitivity Audit**: Analysis of parameter stability across walk-forward windows (WFA) and out-of-sample data.
4. **Actionable Recommendations**: Clear GO / NO-GO deployment recommendation with parameter tuning advice.

---

## 2. Input Context & Prerequisites

The workflow expects one or more of the following data sources:
- **Backtest Result Files**: JSON, CSV, or Markdown outputs in `batch-results/`, `reports/`, or `data/`.
- **Strategy Source Code**: Java/Python strategy files located in `trading-strategies/` or `trading-runtime/`.
- **Configuration Files**: Strategy configuration JSON/YAML files (e.g. `wfa-config.json`).

---

## 3. Data Sources & Path Resolution

- **Strategy Inputs**: `{project-root}/trading-strategies/` or `{project-root}/batch-results/`
- **WFA Configs**: `{project-root}/wfa-config.json`
- **Output Artifact**: `{project-root}/_bmad-output/implementation-artifacts/strategy-review-<strategy-slug>.md`

---

## 4. Execution Steps

### Phase 1: Performance Metrics Extraction
Inspect backtest summary logs and extract fundamental performance KPIs:
- **Return Metrics**: Total Net Profit %, CAGR / Annualized Return.
- **Risk Metrics**: Maximum Peak-to-Trough Drawdown %, Max Drawdown Duration (days/bars).
- **Risk-Adjusted Ratios**: Sharpe Ratio (target > 1.5), Sortino Ratio (target > 2.0), Calmar Ratio.
- **Trade Distribution**: Total Trades, Win Rate %, Profit Factor (Gross Profit / Gross Loss), Average Trade PnL.

### Phase 2: Risk & Overfitting Assessment
- **Parameter Sensitivity**: Check whether small changes in parameters (e.g., EMA period 14 vs 15) cause drastic performance drops (indicator of overfitting).
- **Out-of-Sample / WFA Consistency**: Compare In-Sample (IS) vs. Out-of-Sample (OOS) performance. Flag if OOS degradation exceeds 30%.
- **Regime Robustness**: Assess performance in bull, bear, and high-volatility sideways market regimes.

### Phase 3: Deliverable Generation
Synthesize findings into a clean Markdown report with the structure defined below.

---

## 5. Deliverable Template

The generated report at `{project-root}/_bmad-output/implementation-artifacts/strategy-review-<strategy-slug>.md` must follow this structure:

```markdown
# Strategy Review: [Strategy Name]

**Date:** [YYYY-MM-DD]
**Status:** [GO / CONDITIONAL / NO-GO]
**Target Symbol/Market:** [e.g. NQ, ES, BTC-USD]

## Executive Summary
Brief summary of strategy performance, risk profile, and deployment recommendation.

## Key Performance Indicators (KPIs)
| Metric | Value | Target Benchmark | Pass/Fail |
| :--- | :--- | :--- | :--- |
| **Sharpe Ratio** | 1.85 | > 1.50 | ✅ Pass |
| **Max Drawdown** | -12.4% | < 15.0% | ✅ Pass |
| **Profit Factor** | 1.62 | > 1.40 | ✅ Pass |
| **Win Rate** | 54.2% | > 50.0% | ✅ Pass |
| **Total Trades** | 420 | > 100 | ✅ Pass |

## Overfitting & Walk-Forward Audit
- **In-Sample vs. Out-of-Sample Efficiency**: [e.g., 85% stability]
- **Parameter Sensitivity**: [Robust / Sensitive]

## Key Risks & Weaknesses
- [Risk 1: e.g., Drawdown duration during chop markets]
- [Risk 2: e.g., Execution slippage impact]

## Actionable Next Steps & Recommendations
1. [Recommendation 1]
2. [Recommendation 2]
```

---

## 6. Verification Checklist

- [ ] All key metrics calculated accurately from actual backtest data.
- [ ] OOS vs IS degradation explicitly calculated.
- [ ] Final GO/NO-GO recommendation justified by empirical metrics.
