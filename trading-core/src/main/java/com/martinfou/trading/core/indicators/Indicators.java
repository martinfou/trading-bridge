package com.martinfou.trading.core.indicators;

import com.martinfou.trading.core.Bar;

import java.util.List;

/** Shared bar-based technical indicators for strategies and backtests. */
public final class Indicators {

    private Indicators() {}

    public enum TradeSide { LONG, SHORT }

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
        if (bars.isEmpty()) return Double.NaN;
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

    /** @return {lower band, upper band, band width} */
    public static double[] bollingerWidth(List<Bar> bars, int period, double mult) {
        if (bars.size() < period) return new double[] {Double.NaN, Double.NaN, Double.NaN};
        double mid = smaLatest(bars, period);
        double var = 0;
        for (int i = bars.size() - period; i < bars.size(); i++) {
            double d = bars.get(i).close() - mid;
            var += d * d;
        }
        double std = Math.sqrt(var / period);
        return new double[] {mid - mult * std, mid + mult * std, 2 * mult * std};
    }

    public static double riskRewardTp(double entry, double sl, TradeSide side, double rr) {
        double risk = side == TradeSide.LONG ? entry - sl : sl - entry;
        return side == TradeSide.LONG ? entry + risk * rr : entry - risk * rr;
    }

    /**
     * Calcule une position en units basée sur un % de risque et la volatilité.
     * Plus ATR est élevé, plus la position est petite (risque constant).
     *
     * @param capital Capital de référence en $
     * @param riskPct  % du capital risqué par trade (ex: 0.01 = 1%)
     * @param atr      Valeur ATR actuelle
     * @param atrMult  Multiplicateur ATR pour le SL
     * @param symbol   Symbole de la paire (pour déterminer le pip size)
     * @return Position arrondie à la centaine d'units
     */
    public static long calcRiskPosition(double capital, double riskPct, double atr, double atrMult, String symbol) {
        if (atr <= 0 || capital <= 0) return 1000;
        double pipSize = symbol.contains("JPY") ? 0.01 : 0.0001;
        double slPips = (atr * atrMult) / pipSize;
        if (slPips <= 0) return 1000;
        // Approximation: 1 pip ≈ $10 par lot standard (100k units)
        // Pour JPY: 1 pip ≈ ¥1000 ≈ $6.25 (à 160)
        double pipValuePerUnit = symbol.contains("JPY") ? 0.0000625 : 0.0001;
        double riskAmount = capital * riskPct;
        double units = riskAmount / (slPips * pipValuePerUnit);
        return Math.max(100, Math.round(units / 100.0) * 100);
    }
}
