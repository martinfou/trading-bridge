package com.martinfou.trading.parser.indicators;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.indicators.Indicators;

import java.util.List;

/** SMA / EMA / RSI evaluation with SQ shift and applied price (story 2-4). */
public final class SqCoreIndicators {

    private SqCoreIndicators() {}

    public static double sma(List<Bar> bars, SqIndicatorParams params) {
        int end = params.endIndex(bars.size());
        if (end < params.period() - 1) {
            return Double.NaN;
        }
        double sum = 0;
        for (int i = end - params.period() + 1; i <= end; i++) {
            sum += params.appliedPrice().value(bars.get(i));
        }
        return sum / params.period();
    }

    public static double ema(List<Bar> bars, SqIndicatorParams params) {
        int end = params.endIndex(bars.size());
        if (end < params.period() - 1) {
            return Double.NaN;
        }
        double k = 2.0 / (params.period() + 1);
        double ema = simpleAverage(bars, params, 0, params.period() - 1);
        for (int i = params.period(); i <= end; i++) {
            double price = params.appliedPrice().value(bars.get(i));
            ema = price * k + ema * (1 - k);
        }
        return ema;
    }

    public static double rsi(List<Bar> bars, SqIndicatorParams params) {
        int end = params.endIndex(bars.size());
        int needed = end + 1;
        if (needed < params.period() + 1) {
            return Double.NaN;
        }
        return Indicators.rsi(bars.subList(0, end + 1), params.period());
    }

    static double smaAt(List<Bar> bars, int end, int period, SqAppliedPrice appliedPrice) {
        if (end < period - 1) {
            return Double.NaN;
        }
        double sum = 0;
        for (int i = end - period + 1; i <= end; i++) {
            sum += appliedPrice.value(bars.get(i));
        }
        return sum / period;
    }

    static double emaAt(List<Bar> bars, int end, int period, SqAppliedPrice appliedPrice) {
        if (end < period - 1) {
            return Double.NaN;
        }
        double k = 2.0 / (period + 1);
        double ema = 0;
        for (int i = 0; i < period; i++) {
            ema += appliedPrice.value(bars.get(i));
        }
        ema /= period;
        for (int i = period; i <= end; i++) {
            ema = appliedPrice.value(bars.get(i)) * k + ema * (1 - k);
        }
        return ema;
    }

    private static double simpleAverage(List<Bar> bars, SqIndicatorParams params, int start, int end) {
        double sum = 0;
        for (int i = start; i <= end; i++) {
            sum += params.appliedPrice().value(bars.get(i));
        }
        return sum / (end - start + 1);
    }
}
