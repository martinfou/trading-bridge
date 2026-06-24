package com.martinfou.trading.strategies.prop;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.indicators.Indicators;

/**
 * London Open Range Breakout — breakout of first-hour range with H1 trend filter.
 * Camp: Session Breakout.
 */
public final class LondonOpenRangeBreakoutStrategy extends AbstractPropStrategy {

    private int emaFastPeriod = 20;
    private int emaSlowPeriod = 200;
    private int atrPeriod = 14;

    private double rangeHigh = Double.NaN;
    private double rangeLow = Double.NaN;
    private int rangeDay = -1;
    private boolean rangeLocked;

    public LondonOpenRangeBreakoutStrategy(String symbol) {
        super("Prop_LORB", symbol);
    }

    @Override
    protected void evaluate(Bar bar) {
        if (history.size() < Math.max(emaSlowPeriod, emaFastPeriod) + 20) return;
        int dk = PropSessions.dayKey(bar);
        int h = PropSessions.hour(bar);

        if (dk != rangeDay) {
            rangeDay = dk;
            rangeHigh = Double.NEGATIVE_INFINITY;
            rangeLow = Double.POSITIVE_INFINITY;
            rangeLocked = false;
        }

        if (h >= 7 && h < 8) {
            rangeHigh = Math.max(rangeHigh, bar.high());
            rangeLow = Math.min(rangeLow, bar.low());
        } else if (h == 8 && !rangeLocked && rangeHigh > rangeLow) {
            rangeLocked = true;
        }

        if (!rangeLocked || h < 8 || h > 9) return;

        double ema20 = Indicators.emaLatest(history, emaFastPeriod);
        double ema200 = Indicators.emaLatest(history, emaSlowPeriod);
        double atr = atr(atrPeriod);
        double pip = Indicators.pipSize(symbol);
        double buffer = Math.max(pip, atr * 0.5);

        if (bar.close() > rangeHigh + pip && ema20 > ema200) {
            double entry = bar.close();
            double sl = rangeLow - buffer;
            enterLong(bar, sl, rrTp(entry, sl, Indicators.TradeSide.LONG));
        } else if (bar.close() < rangeLow - pip && ema20 < ema200) {
            double entry = bar.close();
            double sl = rangeHigh + buffer;
            enterShort(bar, sl, rrTp(entry, sl, Indicators.TradeSide.SHORT));
        }
    }
}
