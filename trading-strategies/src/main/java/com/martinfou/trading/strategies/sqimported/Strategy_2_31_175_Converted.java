package com.martinfou.trading.strategies.sqimported;

import com.martinfou.trading.strategies.IndicatorUtils;
import com.martinfou.trading.strategies.SQConvertedStrategyBase;
import com.martinfou.trading.core.Bar;

/**
 * Strategy 2.31.175 — R/R 2.6, PT 370
 * Signal: Vortex(12) crossover (bar 2 vs bar 3)
 * Entry: BUYSTOP at (Close(1) + 2.2 * ATR(20, 3))
 * SL: 145 pips, PT: 370 pips | Expiration: 113 bars | GBP_JPY H1
 */
public class Strategy_2_31_175_Converted extends SQConvertedStrategyBase {

    private static final String NAME = "2.31.175_VortexATR";
    private static final String SYMBOL = "GBP_JPY";
    private static final double QTY = 1000;

    public Strategy_2_31_175_Converted() {
        super(SYMBOL, QTY, JPY_PIP);
        this.minBars = 30;
    }

    @Override
    public String name() { return NAME; }

    @Override
    protected void execute(Bar bar) {
        double v2_plus  = IndicatorUtils.vortexPlus(history, 12, 2);
        double v2_minus = IndicatorUtils.vortexMinus(history, 12, 2);
        double v3_plus  = IndicatorUtils.vortexPlus(history, 12, 3);
        double v3_minus = IndicatorUtils.vortexMinus(history, 12, 3);

        boolean longEntry = (v2_plus > v2_minus) && (v3_plus < v3_minus);

        if (longEntry) {
            double close1 = bar(1).close();
            double atr = IndicatorUtils.atr(history, 20, 3);
            if (atr > 0) {
                placeBuyStop(close1 + 2.2 * atr, 145, 370, 113);
            }
        }
    }
}
