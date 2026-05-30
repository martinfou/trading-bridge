package com.martinfou.trading.strategies.prop;

import com.martinfou.trading.core.Bar;

/**
 * Weekly open gap fade — fade small weekend gaps toward fill.
 * Camp: Mean Reversion / Price Action.
 */
public final class WeeklyOpenGapFadeStrategy extends AbstractPropStrategy {

    public WeeklyOpenGapFadeStrategy(String symbol) {
        super("Prop_GapFade", symbol);
    }

    @Override
    protected int maxHoldBars() {
        return 36;
    }

    @Override
    protected void evaluate(Bar bar) {
        if (history.size() < 10) return;
        if (!PropSessions.isMonday(bar)) return;
        if (!PropSessions.inHourRange(bar, 0, 12)) return;

        double gapPips = PropSessions.weekendGapPips(history, symbol);
        if (Double.isNaN(gapPips)) return;

        double atr = atr(14);
        double pip = PropIndicators.pipSize(symbol);
        double maxGap = atr / pip * 0.3;
        if (Math.abs(gapPips) < 5 || Math.abs(gapPips) > maxGap) return;

        double fridayClose = history.get(history.size() - 2).close();
        for (int i = history.size() - 2; i >= 0; i--) {
            if (PropSessions.utc(history.get(i)).getDayOfWeek().getValue() == 5) {
                fridayClose = history.get(i).close();
                break;
            }
        }

        double gapSize = bar.open() - fridayClose;
        double fillTarget = fridayClose + gapSize * 0.75;

        if (gapPips > 0 && bar.close() < bar.open()) {
            double entry = bar.close();
            double sl = bar.high() + pip * 15;
            enterShort(bar, sl, fillTarget);
        } else if (gapPips < 0 && bar.close() > bar.open()) {
            double entry = bar.close();
            double sl = bar.low() - pip * 15;
            enterLong(bar, sl, fillTarget);
        }
    }
}
