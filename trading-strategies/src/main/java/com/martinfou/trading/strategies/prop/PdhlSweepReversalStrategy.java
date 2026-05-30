package com.martinfou.trading.strategies.prop;

import com.martinfou.trading.core.Bar;

/**
 * Previous Day High/Low liquidity sweep reversal.
 * Camp: Price Action / Liquidity.
 */
public final class PdhlSweepReversalStrategy extends AbstractPropStrategy {

    public PdhlSweepReversalStrategy(String symbol) {
        super("Prop_PDHL_Sweep", symbol);
    }

    @Override
    protected void evaluate(Bar bar) {
        if (history.size() < 30) return;
        if (!PropSessions.inHourRange(bar, 7, 15)) return;

        double[] pd = PropSessions.previousDayHighLow(history);
        double pdh = pd[0];
        double pdl = pd[1];
        if (Double.isNaN(pdh)) return;

        double pip = PropIndicators.pipSize(symbol);
        double atr = atr(14);
        double sweep = pip * 3;

        if (bar.high() > pdh + sweep && bar.close() < pdh && bar.close() < bar.open()) {
            double entry = bar.close();
            double sl = bar.high() + atr * 0.5;
            double tp = (pdh + pdl) / 2.0;
            if ((entry - tp) / (sl - entry) >= 2.0) {
                enterShort(bar, sl, tp);
            }
        } else if (bar.low() < pdl - sweep && bar.close() > pdl && bar.close() > bar.open()) {
            double entry = bar.close();
            double sl = bar.low() - atr * 0.5;
            double tp = (pdh + pdl) / 2.0;
            if ((tp - entry) / (entry - sl) >= 2.0) {
                enterLong(bar, sl, tp);
            }
        }
    }
}
