package com.martinfou.trading.strategies.prop;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.indicators.Indicators;

/**
 * Asian Range Mean Reversion — fade Asian session extremes at London open.
 * Camp: Mean Reversion / Session.
 */
public final class AsianRangeMeanReversionStrategy extends AbstractPropStrategy {

    private double asianHigh = Double.NaN;
    private double asianLow = Double.NaN;
    private int asianDay = -1;

    public AsianRangeMeanReversionStrategy(String symbol) {
        super("Prop_AsianFade", symbol);
    }

    @Override
    protected void evaluate(Bar bar) {
        if (history.size() < 30) return;
        int dk = PropSessions.dayKey(bar);
        int h = PropSessions.hour(bar);

        if (dk != asianDay) {
            asianDay = dk;
            asianHigh = Double.NEGATIVE_INFINITY;
            asianLow = Double.POSITIVE_INFINITY;
        }

        if (h >= 0 && h < 7) {
            asianHigh = Math.max(asianHigh, bar.high());
            asianLow = Math.min(asianLow, bar.low());
        }

        if (h < 7 || h > 8 || asianHigh <= asianLow) return;
        if (PropSessions.isFriday(bar) && h == 7) return;

        Bar prev = history.get(history.size() - 2);
        double rsi = Indicators.rsi(history, 14);
        double atr = atr(14);
        double mid = (asianHigh + asianLow) / 2.0;

        if (bar.high() >= asianHigh - Indicators.pipSize(symbol) && rsi > 70
            && Indicators.isBearishEngulfing(prev, bar)) {
            double entry = bar.close();
            double sl = asianHigh + atr;
            enterShort(bar, sl, mid);
        } else if (bar.low() <= asianLow + Indicators.pipSize(symbol) && rsi < 30
            && Indicators.isBullishEngulfing(prev, bar)) {
            double entry = bar.close();
            double sl = asianLow - atr;
            enterLong(bar, sl, mid);
        }
    }
}
