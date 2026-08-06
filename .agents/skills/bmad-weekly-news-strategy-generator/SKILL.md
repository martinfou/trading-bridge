---
name: bmad-weekly-news-strategy-generator
description: 'Analyzes upcoming macro trading news & economic calendar events for the upcoming week, formulates news-adaptive trading strategies (event breakouts, news blackout filters, volatility regimes), and generates strategy configs and weekly plans.'
---

# Weekly News-Adaptive Trading Strategy Generator

## 1. Purpose & Desired Outcomes

Automatically evaluate high-impact macro economic calendar events scheduled for the upcoming week (e.g. CPI, NFP, FOMC rate decisions, central bank speeches) and generate customized, news-aware trading strategies and configurations.

When this workflow executes, it produces:
1. **Weekly Strategy Plan Artifact**: Saved to `{project-root}/_bmad-output/planning-artifacts/weekly-strategy-plan-<YYYY-MM-DD>.md`.
2. **News Event Risk Calendar**: Risk classification (High, Medium, Low impact) for primary instruments (`EUR_USD`, `GBP_USD`, `USD_JPY`, `NQ`, `ES`).
3. **News Blackout & Entry Rules**: Explicit pre/post news event buffer windows (e.g. suspend new position entry 15 minutes prior to FOMC release).
4. **Updated Strategy Configuration**: Updated parameter grid in `{project-root}/wfa-config.json` or dedicated strategy file in `{project-root}/trading-strategies/`.

---

## 2. Input Context & Data Sources

- **Economic Calendar & Macro News**: Online economic calendar, financial news feeds, or user-provided event schedules.
- **Instrument Specs**: Target instruments (`EUR_USD`, `GBP_USD`, `NQ`, `ES`).
- **Existing Strategy Code & Configs**: Strategy parameters in `wfa-config.json` and strategy classes in `trading-strategies/`.

---

## 3. Data Sources & Path Resolution

- **Strategy Output**: `{project-root}/trading-strategies/`
- **WFA Config Output**: `{project-root}/wfa-config.json`
- **Weekly Strategy Plan Artifact**: `{project-root}/_bmad-output/planning-artifacts/weekly-strategy-plan-<YYYY-MM-DD>.md`

---

## 4. Execution Steps

### Phase 1: Macro News & Economic Calendar Gathering
1. Identify high-impact economic news releases scheduled for the upcoming week (Monday to Friday).
2. Filter for major market-moving catalysts:
   - **USD**: Non-Farm Payrolls (NFP), Consumer Price Index (CPI), FOMC Interest Rate Decision, Powell Speeches.
   - **EUR**: ECB Interest Rate Decision, Eurozone CPI, Lagarde Speeches.
   - **GBP**: Bank of England Rate Decision, UK CPI/GDP.
3. Classify high-volatility danger zones vs. steady-trend windows.

### Phase 2: Strategy Regime Formulation
Based on the weekly calendar:
- **High Event Density Week (e.g., CPI + FOMC)**: Formulate **Volatility Breakout Strategy** with strict post-announcement execution (entering 5–10 mins after event spike to capture follow-through drift).
- **Low Event Density / Rangebound Week**: Formulate **Mean Reversion Strategy** with tight channel bounds.
- **Event Risk Mitigation Rules**: Define mandatory News Blackout Windows (e.g., no new orders 15 mins before to 15 mins after Red-Folder events).

### Phase 3: Strategy Code & Parameter Generation
1. Formulate or update the strategy Java/Python class in `trading-strategies/`.
2. Configure dynamic parameters in `wfa-config.json` (e.g., expanded ATR stop loss during high volatility).

### Phase 4: Plan Artifact Generation
Generate the complete weekly strategy plan document in `_bmad-output/planning-artifacts/weekly-strategy-plan-<YYYY-MM-DD>.md`.

---

## 5. Deliverable Template

The generated weekly plan must follow this format:

```markdown
# Weekly Strategy Plan: Week of [YYYY-MM-DD]

**Generated On:** [YYYY-MM-DD] (Saturday Preparation Run)  
**Target Instruments:** EUR/USD, GBP/USD, NQ  
**Primary Strategy Regime:** [Volatility Expansion / Post-News Momentum / Range Reversion]

---

## 1. High-Impact Economic Calendar (Upcoming Week)

| Day & Time (EST) | Currency | Event | Volatility Impact | Execution Rule |
| :--- | :---: | :--- | :---: | :--- |
| **Tue 08:30 AM** | USD | CPI Inflation Rate | 🔴 High | Blackout 08:15 - 08:45 AM |
| **Wed 02:00 PM** | USD | FOMC Rate Statement | 🔴 High | Blackout 01:45 - 02:30 PM |
| **Fri 08:30 AM** | USD | Non-Farm Payrolls | 🔴 High | Blackout 08:15 - 08:45 AM |

---

## 2. Weekly Strategy Specifications

### Strategy Type: [e.g., Post-News Volatility Breakout]
- **Entry Trigger**: Breakout above/below 15-minute post-news high/low.
- **Stop Loss**: 1.5x ATR (20-period).
- **Take Profit**: 3.0x Risk (1:2 R:R minimum).
- **News Blackout Window**: 15 minutes before and 15 minutes after 🔴 Red-Folder events.

---

## 3. Parameter Configurations (`wfa-config.json`)
```json
{
  "instrument": "EUR_USD",
  "inSampleDays": 180,
  "outOfSampleDays": 60,
  "newsBlackoutBufferMinutes": 15,
  "parameterRanges": [
    { "name": "emaFastPeriod", "min": 10, "max": 20, "step": 5 },
    { "name": "emaSlowPeriod", "min": 50, "max": 100, "step": 10 },
    { "name": "atrMultiplier", "min": 1.5, "max": 2.5, "step": 0.5 }
  ]
}
```

---

## 4. Verification Checklist

- [ ] All 🔴 Red-Folder news events for the upcoming week identified.
- [ ] News blackout buffer windows configured.
- [ ] Strategy parameters and risk limits updated.
```

---

## 6. Execution & Scheduling Instructions

- **Manual Trigger**: Run `/bmad-weekly-news-strategy-generator` on Saturday.
- **Automated Cron**: Schedule to run every Saturday at 09:00 AM using `schedule` tool:
  `CronExpression="0 9 * * 6"`, `Prompt="Run bmad-weekly-news-strategy-generator for the upcoming week"`
