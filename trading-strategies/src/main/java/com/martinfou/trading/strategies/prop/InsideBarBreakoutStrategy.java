package com.martinfou.trading.strategies.prop;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.indicators.Indicators;

/**
 * Inside bar breakout with Bollinger bandwidth compression filter.
 * Camp: Price Action / Momentum.
 */
public final class InsideBarBreakoutStrategy extends AbstractPropStrategy {

    public InsideBarBreakoutStrategy(String symbol) {
        super("Prop_InsideBar", symbol);
    }

    @Override
    protected void evaluate(Bar bar) {
        if (history.size() < 102) return;
        if (!PropSessions.inHourRange(bar, 8, 17)) return;

        Bar inside = history.get(history.size() - 2);
        Bar mother = history.get(history.size() - 3);
        if (!(inside.high() < mother.high() && inside.low() > mother.low())) return;

        double[] widths = new double[100];
        for (int i = 0; i < 100; i++) {
            int end = history.size() - 3 - i;
            if (end < 20) break;
            var sub = history.subList(0, end + 1);
            widths[i] = Indicators.bollingerWidth(sub, 20, 2.0)[2];
        }
        double currentWidth = Indicators.bollingerWidth(history, 20, 2.0)[2];
        double[] sorted = java.util.Arrays.stream(widths).filter(w -> !Double.isNaN(w)).sorted().toArray();
        if (sorted.length < 20) return;
        double p20 = sorted[(int) (sorted.length * 0.20)];
        if (currentWidth > p20) return;

        double pip = Indicators.pipSize(symbol);
        double motherRange = mother.high() - mother.low();
        if (motherRange > atr(14) * 1.5) return;

        if (bar.close() > mother.high() + pip) {
            double entry = bar.close();
            double sl = mother.low() - pip * 2;
            double tp = entry + motherRange * RR;
            enterLong(bar, sl, tp);
        } else if (bar.close() < mother.low() - pip) {
            double entry = bar.close();
            double sl = mother.high() + pip * 2;
            double tp = entry - motherRange * RR;
            enterShort(bar, sl, tp);
        }
    }
}
