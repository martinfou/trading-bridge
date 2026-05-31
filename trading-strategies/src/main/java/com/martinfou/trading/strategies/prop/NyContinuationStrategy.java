package com.martinfou.trading.strategies.prop;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.indicators.Indicators;

/**
 * NY Continuation after London directional move.
 * Camp: Momentum / Session handoff.
 */
public final class NyContinuationStrategy extends AbstractPropStrategy {

    private double londonHigh = Double.NaN;
    private double londonLow = Double.NaN;
    private double londonOpen = Double.NaN;
    private int londonDay = -1;
    private boolean londonTrendUp;

    public NyContinuationStrategy(String symbol) {
        super("Prop_NY_Continuation", symbol);
    }

    @Override
    protected void evaluate(Bar bar) {
        if (history.size() < 30) return;
        int dk = PropSessions.dayKey(bar);
        int h = PropSessions.hour(bar);

        if (dk != londonDay) {
            londonDay = dk;
            londonHigh = Double.NEGATIVE_INFINITY;
            londonLow = Double.POSITIVE_INFINITY;
            londonOpen = Double.NaN;
        }

        if (h >= 7 && h <= 12) {
            if (Double.isNaN(londonOpen)) londonOpen = bar.open();
            londonHigh = Math.max(londonHigh, bar.high());
            londonLow = Math.min(londonLow, bar.low());
        }

        if (h < 12 || h > 14 || Double.isNaN(londonOpen)) return;

        double atrDay = atr(14);
        double londonMove = londonHigh - londonLow;
        if (londonMove < atrDay * 0.5) return;

        londonTrendUp = londonHigh - londonOpen > londonOpen - londonLow;
        double ema20 = Indicators.emaLatest(history, 20);

        if (londonTrendUp && bar.low() <= ema20 && bar.close() > ema20 && bar.close() > bar.open()) {
            double entry = bar.close();
            double sl = Math.min(bar.low(), londonLow) - atrDay * 0.4;
            enterLong(bar, sl, rrTp(entry, sl, Indicators.TradeSide.LONG));
        } else if (!londonTrendUp && bar.high() >= ema20 && bar.close() < ema20 && bar.close() < bar.open()) {
            double entry = bar.close();
            double sl = Math.max(bar.high(), londonHigh) + atrDay * 0.4;
            enterShort(bar, sl, rrTp(entry, sl, Indicators.TradeSide.SHORT));
        }
    }
}
