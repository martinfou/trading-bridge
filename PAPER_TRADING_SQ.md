# 📊 Paper Trading — SQ Strategies

## Sélection des stratégies

Basée sur les backtests 1 an (2025-05 → 2026-05) sur GBP/JPY:

| # | Stratégie | Trades | WR | Return | Sharpe | PF | MaxDD | Verdict |
|---|-----------|--------|----|--------|--------|----|-------|---------|
| 1 | **2_31_177** 🔥 | 45 | 55.6% | **+69.56%** | **1.48** | 1.38 | 25.9% | ✅ PAPER TRADE |
| 2 | **2_32_120** 🔶 | 20 | 40.0% | **+20.38%** | 0.73 | 1.15 | 32.7% | ✅ PAPER TRADE |
| 3 | 2_31_175 | 35 | 40.0% | +0.43% | 0.58 | 1.00 | 56.2% | ❌ Trop risqué |
| 4 | 2_36_190 | 28 | 42.9% | -13.05% | 0.58 | 0.94 | 67.8% | ❌ Perdante |
| 5 | 2_38_112 | 19 | 26.3% | -52.20% | -0.52 | 0.63 | 67.0% | ❌ Perdante |
| 6 | 2_14_147_Adapted | — | — | — | — | — | — | ❌ Pas de backtest |
| 7 | 2_15_195_Adapted | — | — | — | — | — | — | ❌ Pas de backtest |

### Stratégies retenues pour paper trading

### ✅ 2_31_177 — Meilleur R/R (3.1) — PRIORITAIRE
- **Signal:** Open croise au-dessus de LinReg(40)
- **Entry:** BUYSTOP at (Lower Bollinger(10,2) + 1.0 × BBRange(20,2))
- **SL:** 95 pips, **PT:** 290 pips
- **Trailing:** 70 pips (activation à 100)
- **Expiration:** 168 bars
- **Pair:** GBP/JPY
- **Backtest:** 45 trades, 55.6% WR, Sharpe 1.48, +69.56% return

### ✅ 2_32_120 — R/R 3.1, PT 390 — SECONDAIRE
- **Signal:** Vortex(20) crossover (bar 2 vs bar 3)
- **Entry:** BUYSTOP at (Highest(MEDIAN_PRICE, 14, 2) + 1.4 × BiggestRange(30, 3))
- **SL:** 125 pips, **PT:** 390 pips
- **Expiration:** 101 bars
- **Pair:** GBP/JPY
- **Backtest:** 20 trades, 40% WR, Sharpe 0.73, +20.38% return

## Compte Paper Trading
- **Broker:** OANDA Practice (api-fxpractice.oanda.com)
- **Account:** 101-002-4729622-008
- **Balance:** ~$99,581.62 CAD
- **Taille position:** 0.01 lots mini (1,000 units)

## Infrastructure

### Script de lancement
```bash
./scripts/paper-trade-sq.sh [strategy] [granularity] [intervalSec]
```

Exemples:
```bash
# Les 2 meilleures stratégies en séquence (H1, check 60s)
./scripts/paper-trade-sq.sh all H1 60

# Seulement la meilleure stratégie
./scripts/paper-trade-sq.sh 2_31_177 H1 60

# H4, check aux 5 minutes
./scripts/paper-trade-sq.sh 2_31_177 H4 300
```

### Logs
- **Fichier:** `~/logs/paper-trade/paper-trade-YYYYMMDD-HHMMSS.log`
- **State (crash recovery):** `/tmp/live-strategy-state.json`

### Architecture
```
LiveStrategyRunner (Java)
  ├── OandaPriceClient → fetch candles
  ├── Strategy.onBar() → signals
  ├── OandaExecutor → place market/stop orders
  ├── Account monitoring (P&L tracking)
  └── Crash recovery (state persistence)
```

## Monitoring

### Dashboard
Les trades sont visibles sur le [Dashboard Trading](http://localhost:8082) via OANDA API.

### Logs temps réel
```bash
tail -f ~/logs/paper-trade/paper-trade-*.log
```

### État des positions
```bash
cat /tmp/live-strategy-state.json
```

## Risques Paper Trading
- ✋ Les stratégies sont LONG ONLY sur GBP/JPY
- ✋ Le drawdown max historique de 2_31_177 est 25.9%
- ✋ Backtesté sur H1/H4, le paper trade utilise le même timeframe
- ✋ Attention au slippage et spread sur practice vs backtest
