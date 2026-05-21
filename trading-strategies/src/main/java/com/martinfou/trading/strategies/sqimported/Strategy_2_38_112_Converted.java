package com.martinfou.trading.strategies.sqimported;

import com.martinfou.trading.strategies.IndicatorUtils;
import com.martinfou.trading.strategies.SQConvertedStrategyBase;
import com.martinfou.trading.core.Bar;

/**
 * Strategy 2.38.112 — R/R 2.9, SL 110
 * Signal: Vortex(12) crossover (bar 3 vs bar 4) — delayed shift
 * Entry: BUYSTOP at (LWMA(40, LOW, 3) + 1.6 * BiggestRange(50, 3))
 * SL: 110 pips, PT: 320 pips | Expiration: 133 bars | GBP_JPY H1
 */
public class Strategy_2_38_112_Converted extends SQConvertedStrategyBase {

    private static final String NAME = "2.38.112_VortexLWMA";
    private static final String SYMBOL = "GBP_JPY";
    private static final double QTY = 1000;

    public Strategy_2_38_112_Converted() {
        super(SYMBOL, QTY, JPY_PIP);
        this.minBars = 60;
    }

    @Override
    public String name() { return NAME; }

    @Override
    protected void execute(Bar bar) {
        // Vortex(12) crossover with delayed shift: bar 3 vs bar 4
        double v3_plus  = IndicatorUtils.vortexPlus(history, 12, 3);
        double v3_minus = IndicatorUtils.vortexMinus(history, 12, 3);
        double v4_plus  = IndicatorUtils.vortexPlus(history, 12, 4);
        double v4_minus = IndicatorUtils.vortexMinus(history, 12, 4);

        boolean longEntry = (v3_plus > v3_minus) && (v4_plus < v4_minus);

        if (longEntry) {
            double lwma    = IndicatorUtils.lwma(history, 40, "low", 3);
            double bigRange = IndicatorUtils.biggestRange(history, 50, 3);
            if (lwma > 0 && bigRange > 0) {
                placeBuyStop(lwma + 1.6 * bigRange, 110, 320, 133);
            }
        }
    }
}
