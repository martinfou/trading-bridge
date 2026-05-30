package com.martinfou.trading.strategies.prop;

import com.martinfou.trading.core.Bar;

/**
 * Connors RSI(2) mean reversion with 200-SMA trend filter.
 * Camp: Mean Reversion.
 */
public final class ConnorsRsi2Strategy extends AbstractPropStrategy {

    public ConnorsRsi2Strategy(String symbol) {
        super("Prop_ConnorsRSI2", symbol);
    }

    @Override
    protected int maxHoldBars() {
        return 24;
    }

    @Override
    protected void evaluate(Bar bar) {
        if (history.size() < 205) return;

        double sma200 = PropIndicators.smaLatest(history, 200);
        double rsi2 = PropIndicators.rsi2(history);
        double atr = atr(14);

        if (bar.close() > sma200 && rsi2 < 10) {
            double entry = bar.close();
            double sl = entry - atr * 1.5;
            enterLong(bar, sl, rrTp(entry, sl, PropIndicators.OrderSide.LONG));
        } else if (bar.close() < sma200 && rsi2 > 90) {
            double entry = bar.close();
            double sl = entry + atr * 1.5;
            enterShort(bar, sl, rrTp(entry, sl, PropIndicators.OrderSide.SHORT));
        }
    }
}
