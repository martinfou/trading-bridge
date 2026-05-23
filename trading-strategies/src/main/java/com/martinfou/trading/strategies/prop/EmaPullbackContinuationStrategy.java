package com.martinfou.trading.strategies.prop;

import com.martinfou.trading.core.Bar;

/**
 * EMA Pullback Continuation — trend pullback to 20-EMA in established trend.
 * Camp: Momentum.
 */
public final class EmaPullbackContinuationStrategy extends AbstractPropStrategy {

    public EmaPullbackContinuationStrategy(String symbol) {
        super("Prop_EMAPullback", symbol);
    }

    @Override
    protected void evaluate(Bar bar) {
        if (history.size() < 210) return;
        if (PropSessions.inHourRange(bar, 21, 1)) return;

        double ema20 = PropIndicators.emaLatest(history, 20);
        double ema50 = PropIndicators.emaLatest(history, 50);
        double ema200 = PropIndicators.emaLatest(history, 200);
        double rsi = PropIndicators.rsi(history, 14);
        double atr = atr(14);

        if (ema50 > ema200 && bar.low() <= ema20 && bar.close() > ema20
            && bar.close() > bar.open() && rsi >= 40 && rsi <= 60) {
            double entry = bar.close();
            double sl = Math.min(bar.low(), ema50) - atr * 0.3;
            enterLong(bar, sl, rrTp(entry, sl, PropIndicators.OrderSide.LONG));
        } else if (ema50 < ema200 && bar.high() >= ema20 && bar.close() < ema20
            && bar.close() < bar.open() && rsi >= 40 && rsi <= 60) {
            double entry = bar.close();
            double sl = Math.max(bar.high(), ema50) + atr * 0.3;
            enterShort(bar, sl, rrTp(entry, sl, PropIndicators.OrderSide.SHORT));
        }
    }
}
