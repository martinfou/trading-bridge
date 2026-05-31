package com.martinfou.trading.strategies.prop;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.indicators.Indicators;

/**
 * Supply/Demand Zone Rejection — trade origin of last impulsive H4-equivalent leg.
 * Camp: Supply/Demand (approximated on bar series with 4× lookback).
 */
public final class SupplyDemandZoneStrategy extends AbstractPropStrategy {

    private static final int IMPULSE_LOOKBACK = 4;
    private static final int BASE_MAX_BARS = 3;

    public SupplyDemandZoneStrategy(String symbol) {
        super("Prop_SupplyDemand", symbol);
    }

    @Override
    protected void evaluate(Bar bar) {
        if (history.size() < 50) return;
        if (!PropSessions.inHourRange(bar, 8, 16)) return;

        int end = history.size() - 1;
        double atr = atr(14);
        double move = Math.abs(history.get(end).close() - history.get(end - IMPULSE_LOOKBACK).close());
        if (move < atr * 1.5) return;

        boolean bullishImpulse = history.get(end).close() > history.get(end - IMPULSE_LOOKBACK).close();
        double zoneHigh = Double.NEGATIVE_INFINITY;
        double zoneLow = Double.POSITIVE_INFINITY;
        for (int i = end - IMPULSE_LOOKBACK; i >= Math.max(0, end - IMPULSE_LOOKBACK - BASE_MAX_BARS); i--) {
            zoneHigh = Math.max(zoneHigh, history.get(i).high());
            zoneLow = Math.min(zoneLow, history.get(i).low());
        }

        Bar prev = history.get(end - 1);
        if (bullishImpulse && bar.low() <= zoneHigh && bar.close() > zoneLow
            && bar.low() > prev.low() && bar.close() > bar.open()) {
            double entry = bar.close();
            double sl = zoneLow - Math.max(Indicators.pipSize(symbol) * 5, atr * 0.3);
            enterLong(bar, sl, rrTp(entry, sl, Indicators.TradeSide.LONG));
        } else if (!bullishImpulse && bar.high() >= zoneLow && bar.close() < zoneHigh
            && bar.high() < prev.high() && bar.close() < bar.open()) {
            double entry = bar.close();
            double sl = zoneHigh + Math.max(Indicators.pipSize(symbol) * 5, atr * 0.3);
            enterShort(bar, sl, rrTp(entry, sl, Indicators.TradeSide.SHORT));
        }
    }
}
