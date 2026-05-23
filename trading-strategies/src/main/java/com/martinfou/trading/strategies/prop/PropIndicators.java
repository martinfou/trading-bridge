package com.martinfou.trading.strategies.prop;

import com.martinfou.trading.core.Bar;

import java.util.List;

/** Technical indicators for mechanical prop-firm strategies. */
public final class PropIndicators {

    private PropIndicators() {}

    public static double pipSize(String symbol) {
        return symbol.contains("JPY") ? 0.01 : 0.0001;
    }

    public static double sma(List<Bar> bars, int period, int endIndex) {
        if (endIndex < period - 1) return Double.NaN;
        double sum = 0;
        for (int i = endIndex - period + 1; i <= endIndex; i++) {
            sum += bars.get(i).close();
        }
        return sum / period;
    }

    public static double smaLatest(List<Bar> bars, int period) {
        return sma(bars, period, bars.size() - 1);
    }

    public static double emaLatest(List<Bar> bars, int period) {
        if (bars.size() < period) return Double.NaN;
        double k = 2.0 / (period + 1);
        double ema = sma(bars, period, period - 1);
        for (int i = period; i < bars.size(); i++) {
            ema = bars.get(i).close() * k + ema * (1 - k);
        }
        return ema;
    }

    public static double atr(List<Bar> bars, int period) {
        if (bars.size() < period + 1) return Double.NaN;
        double sum = 0;
        int end = bars.size() - 1;
        for (int i = end - period + 1; i <= end; i++) {
            Bar cur = bars.get(i);
            Bar prev = bars.get(i - 1);
            double tr = Math.max(cur.high() - cur.low(),
                Math.max(Math.abs(cur.high() - prev.close()), Math.abs(cur.low() - prev.close())));
            sum += tr;
        }
        return sum / period;
    }

    public static double rsi(List<Bar> bars, int period) {
        if (bars.size() < period + 1) return Double.NaN;
        double gain = 0, loss = 0;
        int end = bars.size() - 1;
        for (int i = end - period + 1; i <= end; i++) {
            double diff = bars.get(i).close() - bars.get(i - 1).close();
            if (diff >= 0) gain += diff;
            else loss -= diff;
        }
        if (loss == 0) return 100;
        double rs = gain / loss;
        return 100 - (100 / (1 + rs));
    }

    public static double rsi2(List<Bar> bars) {
        if (bars.size() < 3) return Double.NaN;
        double gain = 0, loss = 0;
        for (int i = bars.size() - 2; i < bars.size(); i++) {
            double diff = bars.get(i).close() - bars.get(i - 1).close();
            if (diff >= 0) gain += diff;
            else loss -= diff;
        }
        if (loss == 0) return 100;
        return 100 - (100 / (1 + gain / loss));
    }

    public static boolean isBullishEngulfing(Bar prev, Bar cur) {
        return prev.close() < prev.open()
            && cur.close() > cur.open()
            && cur.close() > prev.open()
            && cur.open() < prev.close();
    }

    public static boolean isBearishEngulfing(Bar prev, Bar cur) {
        return prev.close() > prev.open()
            && cur.close() < cur.open()
            && cur.close() < prev.open()
            && cur.open() > prev.close();
    }

    public static double[] bollingerWidth(List<Bar> bars, int period, double mult) {
        if (bars.size() < period) return new double[] {Double.NaN, Double.NaN};
        double mid = smaLatest(bars, period);
        double var = 0;
        for (int i = bars.size() - period; i < bars.size(); i++) {
            double d = bars.get(i).close() - mid;
            var += d * d;
        }
        double std = Math.sqrt(var / period);
        return new double[] {mid - mult * std, mid + mult * std, 2 * mult * std};
    }

    public static double riskRewardTp(double entry, double sl, OrderSide side, double rr) {
        double risk = side == OrderSide.LONG ? entry - sl : sl - entry;
        return side == OrderSide.LONG ? entry + risk * rr : entry - risk * rr;
    }

    public enum OrderSide { LONG, SHORT }
}
