package com.martinfou.trading.strategies.prop;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.indicators.Indicators;

/**
 * London–NY overlap momentum burst from 2-hour consolidation.
 * Camp: Session Breakout.
 */
public final class OverlapMomentumBurstStrategy extends AbstractPropStrategy {

    private double rangeHigh = Double.NaN;
    private double rangeLow = Double.NaN;
    private int rangeDay = -1;
    private boolean rangeReady;

    public OverlapMomentumBurstStrategy(String symbol) {
        super("Prop_OverlapBurst", symbol);
    }

    @Override
    protected void evaluate(Bar bar) {
        if (history.size() < 30) return;
        int dk = PropSessions.dayKey(bar);
        int h = PropSessions.hour(bar);

        if (dk != rangeDay) {
            rangeDay = dk;
            rangeHigh = Double.NEGATIVE_INFINITY;
            rangeLow = Double.POSITIVE_INFINITY;
            rangeReady = false;
        }

        if (h >= 10 && h < 12) {
            rangeHigh = Math.max(rangeHigh, bar.high());
            rangeLow = Math.min(rangeLow, bar.low());
        } else if (h == 12 && rangeHigh > rangeLow) {
            rangeReady = true;
        }

        if (!rangeReady || h < 12 || h > 16) return;

        double range = rangeHigh - rangeLow;
        if (range <= 0) return;

        double pip = Indicators.pipSize(symbol);
        if (bar.close() > rangeHigh + pip && bar.close() > bar.open()) {
            double entry = bar.close();
            double sl = rangeLow - range * 0.3;
            double tp = entry + range * RR;
            enterLong(bar, sl, tp);
        } else if (bar.close() < rangeLow - pip && bar.close() < bar.open()) {
            double entry = bar.close();
            double sl = rangeHigh + range * 0.3;
            double tp = entry - range * RR;
            enterShort(bar, sl, tp);
        }
    }
}
