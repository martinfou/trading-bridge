# Weekly Strategy Plan: Week of August 10, 2026

**Generated On:** 2026-08-08 (Saturday Preparation Run)  
**Target Instruments:** EUR/USD, GBP/USD, NQ, ES  
**Primary Strategy Regime:** Post-News Volatility Expansion & Momentum Drift  

---

## 1. High-Impact Economic Calendar (Aug 10 - Aug 14, 2026)

| Day & Time (EST) | Currency | Event | Volatility Impact | Execution & Blackout Rule |
| :--- | :---: | :--- | :---: | :--- |
| **Wed Aug 12, 08:30 AM** | USD | Consumer Price Index (CPI) | 🔴 High | Blackout 08:15 AM – 08:45 AM |
| **Thu Aug 13, 08:30 AM** | USD | Producer Price Index (PPI) | 🟠 Medium | Blackout 08:20 AM – 08:40 AM |
| **Fri Aug 14, 08:30 AM** | USD | US Retail Sales | 🔴 High | Blackout 08:15 AM – 08:45 AM |
| **Fri Aug 14, 10:00 AM** | USD | U. Michigan Consumer Sentiment | 🟡 Medium | Standard volatility trailing stop |

> ⚠️ **FOMC Note:** No FOMC Interest Rate Decision meeting scheduled in August 2026. Next FOMC meeting is Sept 15–16, 2026.

---

## 2. Weekly Strategy Specifications

### Strategy Type: Post-News Volatility Expansion & Momentum Drift
* **Core Logic**: During high-impact news release windows (CPI, Retail Sales), price action often experiences initial whipsaw volatility followed by a sustained directional drift.
* **Entry Rule**:
  - Wait 15 minutes post-release until initial news candle closes.
  - Enter Long/Short on 5-minute candle breakout above/below 15-minute post-news high/low.
* **Exit & Risk Management**:
  - **Stop Loss**: 1.5x ATR (14-period).
  - **Take Profit Target**: 3.0x Risk (1:2.0 R:R minimum ratio).
  - **Trailing Stop**: Activate trailing stop after 1.5x R:R profit reached.
* **News Blackout Buffer**:
  - Automatically halt new position entries **15 minutes before** and **15 minutes after** 🔴 Red-Folder events (CPI on Wed, Retail Sales on Fri).

---

## 3. Parameter Configurations (`wfa-config.json`)

```json
{
  "instrument": "EUR_USD",
  "initialCapital": 10000.0,
  "inSampleDays": 180,
  "outOfSampleDays": 60,
  "anchored": false,
  "newsBlackoutBufferMinutes": 15,
  "weeklyStrategyRegime": "Post-News Volatility Expansion & Momentum Drift",
  "parameterRanges": [
    { "name": "emaFastPeriod", "min": 5.0, "max": 50.0, "step": 5.0 },
    { "name": "emaSlowPeriod", "min": 50.0, "max": 300.0, "step": 25.0 },
    { "name": "atrPeriod", "min": 10.0, "max": 20.0, "step": 2.0 },
    { "name": "atrMultiplier", "min": 1.5, "max": 3.0, "step": 0.5 }
  ]
}
```

---

## 4. Verification Checklist

- [x] All 🔴 Red-Folder economic releases for the upcoming week mapped.
- [x] Pre/Post news blackout windows (15-min buffers) established.
- [x] Parameter search space in `wfa-config.json` expanded for robust grid optimization.
- [x] Post-news momentum breakout logic configured.
