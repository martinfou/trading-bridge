package com.martinfou.trading.strategies.seasonality;

import com.martinfou.trading.core.Order;

/** GBP/USD — Spring Bullish (83% hit rate). Entry: Mar 11 → Exit: Apr 25 */
public class GbpUsdSpringBullish extends SeasonalityStrategy {
    public GbpUsdSpringBullish() {
        super("GbpUsdSpringBullish", "GBP_USD",
            new SeasonalEntry(3, 11), new SeasonalExit(4, 25),
            Order.Side.BUY, 200, 400, 1000);
    }
}
