package com.martinfou.trading.strategies.sqimported;

import com.martinfou.trading.strategies.IndicatorUtils;
import com.martinfou.trading.strategies.SQConvertedStrategyBase;
import com.martinfou.trading.core.Bar;

/**
 * Strategy 2.36.190 — R/R 2.7, PT 395 (le plus haut)
 * Signal: Vortex(20) crossover (bar 2 vs bar 3)
 * Entry: BUYSTOP at (High(2) + 1.8 * BBRange(10, 0.9, CLOSE, 1))
 * SL: 145 pips, PT: 395 pips | Expiration: 198 bars | GBP_JPY H1
 */
public class Strategy_2_36_190_Converted extends SQConvertedStrategyBase {

    private static final String NAME = "2.36.190_VortexBB";
    private static final String SYMBOL = "GBP_JPY";
    private static final double QTY = 1000;

    public Strategy_2_36_190_Converted() {
        super(SYMBOL, QTY, JPY_PIP);
        this.minBars = 30;
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
            double high2   = bar(2).high();
            double bbRange = IndicatorUtils.bbRange(history, 10, 0.9, "close", 1);
            if (bbRange > 0) {
                placeBuyStop(high2 + 1.8 * bbRange, 145, 395, 198);
            }
        }
    }
}
