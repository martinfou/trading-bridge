package com.martinfou.trading.strategies;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.indicators.Indicators;

import java.util.List;

/** Strategy-layer market analysis built on {@link Indicators}. */
public final class MarketAnalyzer {

    private MarketAnalyzer() {}

    public static double rsi(List<Bar> bars, int period) {
        if (bars.size() < period + 1) return 50;
        double value = Indicators.rsi(bars, period);
        return Double.isNaN(value) ? 50 : value;
    }

    public static double sma(List<Bar> bars, int period) {
        if (bars.size() < period) {
            return bars.isEmpty() ? 0 : bars.getLast().close();
        }
        return Indicators.smaLatest(bars, period);
    }

    /** Simplified range ATR (high − low) for legacy news-trading heuristics. */
    public static double atr(List<Bar> bars, int period) {
        int size = bars.size();
        if (size < period) return 0;
        double sum = 0;
        for (int i = size - period; i < size; i++) {
            Bar b = bars.get(i);
            sum += b.high() - b.low();
        }
        return sum / period;
    }

    public static double[] findKeyLevels(List<Bar> bars, int lookback) {
        int size = bars.size();
        double high = Double.MIN_VALUE, low = Double.MAX_VALUE;
        for (int i = size - lookback; i < size; i++) {
            Bar b = bars.get(i);
            if (b.high() > high) high = b.high();
            if (b.low() < low) low = b.low();
        }
        double mid = (high + low) / 2;
        return new double[]{low, mid, high};
    }

    public static String trend(List<Bar> bars, int fast, int slow) {
        if (bars.size() < slow) return "NEUTRAL";
        double fastSma = sma(bars, fast);
        double slowSma = sma(bars, slow);
        if (fastSma > slowSma * 1.001) return "BULLISH";
        if (fastSma < slowSma * 0.999) return "BEARISH";
        return "NEUTRAL";
    }
}
