package com.martinfou.trading.strategies.sqimported;

import com.martinfou.trading.strategies.IndicatorUtils;
import com.martinfou.trading.strategies.SQConvertedStrategyBase;
import com.martinfou.trading.core.Bar;

/**
 * Strategy 2.32.120 — R/R 3.1, PT 390
 * Signal: Vortex(20) crossover (bar 2 vs bar 3)
 * Entry: BUYSTOP at (Highest(MEDIAN_PRICE, 14, 2) + 1.4 * BiggestRange(30, 3))
 * SL: 125 pips, PT: 390 pips | Expiration: 101 bars | GBP_JPY H1
 */
public class Strategy_2_32_120_Converted extends SQConvertedStrategyBase {

    private static final String NAME = "2.32.120_VortexMedian";
    private static final String SYMBOL = "GBP_JPY";
    private static final double QTY = 1000;

    public Strategy_2_32_120_Converted() {
        super(SYMBOL, QTY, JPY_PIP);
        this.minBars = 40;
    }

    @Override
    public String name() { return NAME; }

    @Override
    protected void execute(Bar bar) {
        double v2_plus  = IndicatorUtils.vortexPlus(history, 20, 2);
        double v2_minus = IndicatorUtils.vortexMinus(history, 20, 2);
        double v3_plus  = IndicatorUtils.vortexPlus(history, 20, 3);
        double v3_minus = IndicatorUtils.vortexMinus(history, 20, 3);

        boolean longEntry = (v2_plus > v2_minus) && (v3_plus < v3_minus);

        if (longEntry) {
            double highestMedian = 0;
            for (int i = history.size() - 2 - 14; i <= history.size() - 2; i++) {
                double median = (history.get(i).high() + history.get(i).low()) / 2.0;
                if (median > highestMedian) highestMedian = median;
            }
            double biggestRange = IndicatorUtils.biggestRange(history, 30, 3);
            if (biggestRange > 0) {
                placeBuyStop(highestMedian + 1.4 * biggestRange, 125, 390, 101);
            }
        }
    }
}
