package com.martinfou.trading.strategies;

import com.martinfou.trading.core.Bar;
import java.util.List;

public class MarketAnalyzer {
    
    // RSI calculation
    public static double rsi(List<Bar> bars, int period) {
        if (bars.size() < period + 1) return 50;
        double gain = 0, loss = 0;
        for (int i = bars.size() - period; i < bars.size(); i++) {
            double diff = bars.get(i).close() - bars.get(i-1).close();
            if (diff > 0) gain += diff; else loss -= diff;
        }
        double avgGain = gain / period, avgLoss = loss / period;
        if (avgLoss == 0) return 100;
        return 100 - (100 / (1 + avgGain / avgLoss));
    }

    // SMA
    public static double sma(List<Bar> bars, int period) {
        int size = bars.size();
        double sum = 0;
        for (int i = size - period; i < size; i++) sum += bars.get(i).close();
        return sum / period;
    }

    // ATR for stop loss distance
    public static double atr(List<Bar> bars, int period) {
        int size = bars.size();
        double sum = 0;
        for (int i = size - period; i < size; i++) {
            Bar b = bars.get(i);
            sum += b.high() - b.low();
        }
        return sum / period;
    }

    // Support/Resistance levels
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

    // Trend direction
    public static String trend(List<Bar> bars, int fast, int slow) {
        if (bars.size() < slow) return "NEUTRAL";
        double fastSma = sma(bars, fast);
        double slowSma = sma(bars, slow);
        if (fastSma > slowSma * 1.001) return "BULLISH";
        if (fastSma < slowSma * 0.999) return "BEARISH";
        return "NEUTRAL";
    }
}
