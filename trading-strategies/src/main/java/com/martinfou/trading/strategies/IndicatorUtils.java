package com.martinfou.trading.strategies;

import com.martinfou.trading.core.Bar;
import java.util.List;

/**
 * Shared indicator calculations used by StrategyQuant-imported strategies.
 * <p>
 * Eliminates the massive code duplication across all 7+ converted strategies
 * by providing a single source of truth for LinReg, BB, ATR, Vortex, LWMA,
 * SMA, BiggestRange, and PriceType extraction.
 */
public final class IndicatorUtils {

    private IndicatorUtils() {}

    // ----------------------------------------------------------------
    //  Price-type extraction
    // ----------------------------------------------------------------

    /**
     * Returns the named price field from a Bar.
     * Supported types: open, high, low, close, median, typical.
     */
    public static double getPrice(Bar bar, String type) {
        return switch (type.toLowerCase()) {
            case "open" -> bar.open();
            case "high" -> bar.high();
            case "low" -> bar.low();
            case "close" -> bar.close();
            case "median" -> (bar.high() + bar.low()) / 2.0;
            case "typical" -> (bar.high() + bar.low() + bar.close()) / 3.0;
            default -> bar.close();
        };
    }

    // ----------------------------------------------------------------
    //  SMA
    // ----------------------------------------------------------------

    /**
     * Simple Moving Average over a given period at a given shift.
     *
     * @param bars      full bar history
     * @param period    lookback period
     * @param priceType price field (close, open, high, low, median, typical)
     * @param shift     how many bars back from current (0 = current bar)
     * @return SMA value, or 0 if insufficient data
     */
    public static double sma(List<Bar> bars, int period, String priceType, int shift) {
        int end = bars.size() - 1 - shift;
        int start = end - period + 1;
        if (start < 0) return 0;
        double sum = 0;
        for (int i = start; i <= end; i++) {
            sum += getPrice(bars.get(i), priceType);
        }
        return sum / period;
    }

    // ----------------------------------------------------------------
    //  LWMA (Linear Weighted Moving Average)
    // ----------------------------------------------------------------

    /**
     * Linear Weighted Moving Average.
     * LWMA = sum(price[i] * weight[i]) / sum(weights), weight[i] = i-start+1
     */
    public static double lwma(List<Bar> bars, int period, String priceType, int shift) {
        int end = bars.size() - 1 - shift;
        int start = end - period + 1;
        if (start < 0) return 0;
        double sumWeightedPrice = 0, sumWeight = 0;
        int weight = 1;
        for (int i = start; i <= end; i++) {
            sumWeightedPrice += getPrice(bars.get(i), priceType) * weight;
            sumWeight += weight;
            weight++;
        }
        return sumWeightedPrice / sumWeight;
    }

    // ----------------------------------------------------------------
    //  Linear Regression Indicator
    // ----------------------------------------------------------------

    /**
     * Linear Regression (OLS) indicator value at the given shift.
     * y = a + b*x where b = covariance(x, y) / variance(x)
     */
    public static double linReg(List<Bar> bars, int period, String priceType, int shift) {
        int end = bars.size() - 1 - shift;
        int start = end - period + 1;
        if (start < 0) return 0;
        double sumX = 0, sumY = 0;
        int n = period;
        for (int i = 0; i < n; i++) {
            sumX += i;
            sumY += getPrice(bars.get(start + i), priceType);
        }
        double meanX = sumX / n;
        double meanY = sumY / n;
        double covariance = 0, varianceX = 0;
        for (int i = 0; i < n; i++) {
            double xi = i;
            double yi = getPrice(bars.get(start + i), priceType);
            covariance += (xi - meanX) * (yi - meanY);
            varianceX += (xi - meanX) * (xi - meanX);
        }
        if (varianceX == 0) return meanY;
        double slope = covariance / varianceX;
        double intercept = meanY - slope * meanX;
        return intercept + slope * (n - 1);
    }

    // ----------------------------------------------------------------
    //  Bollinger Band Width (BBRange)
    // ----------------------------------------------------------------

    /**
     * Bollinger Bands Range = upperBand - lowerBand = 2 * mult * StdDev.
     */
    public static double bbRange(List<Bar> bars, int period, double mult, String priceType, int shift) {
        double sma = sma(bars, period, priceType, shift);
        if (sma == 0) return 0;
        int end = bars.size() - 1 - shift;
        int start = end - period + 1;
        if (start < 0) return 0;
        double variance = 0;
        for (int i = start; i <= end; i++) {
            double diff = getPrice(bars.get(i), priceType) - sma;
            variance += diff * diff;
        }
        double stdDev = Math.sqrt(variance / period);
        return 2.0 * mult * stdDev;
    }

    // ----------------------------------------------------------------
    //  ATR (Average True Range)
    // ----------------------------------------------------------------

    /** Average True Range over the given period at a given shift. */
    public static double atr(List<Bar> bars, int period, int shift) {
        int end = bars.size() - 1 - shift;
        int start = end - period + 1;
        if (start < 1) return 0;
        double sumTr = 0;
        for (int i = start; i <= end; i++) {
            double tr1 = bars.get(i).high() - bars.get(i).low();
            double tr2 = Math.abs(bars.get(i).high() - bars.get(i - 1).close());
            double tr3 = Math.abs(bars.get(i).low() - bars.get(i - 1).close());
            sumTr += Math.max(tr1, Math.max(tr2, tr3));
        }
        return sumTr / period;
    }

    // ----------------------------------------------------------------
    //  Vortex Indicator
    // ----------------------------------------------------------------

    /** VI+ over the given period at a given shift. */
    public static double vortexPlus(List<Bar> bars, int period, int shift) {
        int end = bars.size() - 1 - shift;
        int start = end - period + 1;
        if (start < 1) return 0;
        double sumVmPlus = 0, sumTr = 0;
        for (int i = start; i <= end; i++) {
            double tr1 = bars.get(i).high() - bars.get(i).low();
            double tr2 = Math.abs(bars.get(i).high() - bars.get(i - 1).close());
            double tr3 = Math.abs(bars.get(i).low() - bars.get(i - 1).close());
            sumTr += Math.max(tr1, Math.max(tr2, tr3));
            sumVmPlus += Math.abs(bars.get(i).high() - bars.get(i - 1).low());
        }
        return sumTr > 0 ? sumVmPlus / sumTr : 0;
    }

    /** VI- over the given period at a given shift. */
    public static double vortexMinus(List<Bar> bars, int period, int shift) {
        int end = bars.size() - 1 - shift;
        int start = end - period + 1;
        if (start < 1) return 0;
        double sumVmMinus = 0, sumTr = 0;
        for (int i = start; i <= end; i++) {
            double tr1 = bars.get(i).high() - bars.get(i).low();
            double tr2 = Math.abs(bars.get(i).high() - bars.get(i - 1).close());
            double tr3 = Math.abs(bars.get(i).low() - bars.get(i - 1).close());
            sumTr += Math.max(tr1, Math.max(tr2, tr3));
            sumVmMinus += Math.abs(bars.get(i).low() - bars.get(i - 1).high());
        }
        return sumTr > 0 ? sumVmMinus / sumTr : 0;
    }

    // ----------------------------------------------------------------
    //  Biggest Range (max High-Low over period)
    // ----------------------------------------------------------------

    /** Maximum High-Low range over the given period at a given shift. */
    public static double biggestRange(List<Bar> bars, int period, int shift) {
        int end = bars.size() - 1 - shift;
        int start = end - period + 1;
        if (start < 0) return 0;
        double maxRange = 0;
        for (int i = start; i <= end; i++) {
            double range = bars.get(i).high() - bars.get(i).low();
            if (range > maxRange) maxRange = range;
        }
        return maxRange;
    }
}
