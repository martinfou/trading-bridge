package com.martinfou.trading.parser.indicators;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.indicators.Indicators;

import java.util.List;

/** MACD, Bollinger, ATR evaluation with SQ shift (story 2-5). */
public final class SqExtendedIndicators {

    private SqExtendedIndicators() {}

    public static double atr(List<Bar> bars, SqIndicatorParams params) {
        int end = params.endIndex(bars.size());
        if (end < params.period()) {
            return Double.NaN;
        }
        return Indicators.atr(bars.subList(0, end + 1), params.period());
    }

    public static double bollingerMiddle(List<Bar> bars, SqBollingerParams params) {
        return SqCoreIndicators.smaAt(
            bars,
            params.endIndex(bars.size()),
            params.period(),
            params.appliedPrice()
        );
    }

    public static double bbRange(List<Bar> bars, SqBollingerParams params) {
        int end = params.endIndex(bars.size());
        if (end < params.period() - 1) {
            return Double.NaN;
        }
        double mid = SqCoreIndicators.smaAt(bars, end, params.period(), params.appliedPrice());
        if (Double.isNaN(mid)) {
            return Double.NaN;
        }
        double variance = 0;
        for (int i = end - params.period() + 1; i <= end; i++) {
            double diff = params.appliedPrice().value(bars.get(i)) - mid;
            variance += diff * diff;
        }
        double stdDev = Math.sqrt(variance / params.period());
        return 2.0 * params.deviation() * stdDev;
    }

    public static double macdLine(List<Bar> bars, SqMacdParams params) {
        int end = params.endIndex(bars.size());
        if (end < params.slowPeriod() - 1) {
            return Double.NaN;
        }
        double fast = SqCoreIndicators.emaAt(bars, end, params.fastPeriod(), params.appliedPrice());
        double slow = SqCoreIndicators.emaAt(bars, end, params.slowPeriod(), params.appliedPrice());
        if (Double.isNaN(fast) || Double.isNaN(slow)) {
            return Double.NaN;
        }
        return fast - slow;
    }
}
