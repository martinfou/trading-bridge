package com.martinfou.trading.strategies.seasonality;

import com.martinfou.trading.core.Order;

/**
 * ⭐ USDCAD — Autumn Bullish (94% hit rate)
 *
 * Entry: Oct 12 → Exit: Nov 26
 * Thesis: End of driving season → oil ↓ → CAD ↓ → USDCAD ↑
 * Hit rate: 94% (15/16 years positive)
 * Avg return: +1.65%
 */
public class UsdCadAutumnBullish extends SeasonalityStrategy {
    public UsdCadAutumnBullish() {
        super("UsdCadAutumnBullish", "USDCAD",
            new SeasonalEntry(10, 12), new SeasonalExit(11, 26),
            Order.Side.BUY, 150, 300, 1000);
    }
}
