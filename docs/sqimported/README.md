# Imported StrategyQuant Strategies

## Strategy 1.10.168 (GBPJPY_ADX_BB_v1)
- Signal: ADX(14) rising + HighestInRange/WC(50) crossover
- Entry: High[2] + 0.6 * BBRange(50, 1.3)[2]
- SL: 150 | PT: 365 | H1 timeframe

## Strategy 1.1.141 (GBPJPY_ADX_BB_v2)
- Signal: ADX(14) rising at shift 4 + HighestInRange/OPEN(10) crossover
- Entry: BBUpper(50, 2.1)[1] + 0.6 * BBRange(20, 2.0)[2]
- SL: 150 | PT: 215 | H1 timeframe
- Note: Uses Bollinger Band upper instead of High for entry
