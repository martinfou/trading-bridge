package com.martinfou.trading.strategies.generated;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import java.util.ArrayList;
import java.util.List;

/**
 * Top20_Mean_reversion_7_RSI18 — auto-generated from a genetic-algorithm chromosome.
 *
 * <p>Entry conditions:
 * <ul>
 *   <li>RSI(18, CLOSE)</li>
 *   <li>EMA(17, CLOSE)</li>
 * </ul>
 * <p>Exit conditions:
 * <ul>
 *   <li>SMA(66, CLOSE)</li>
 * </ul>
 */
public class Top20_Mean_reversion_7_RSI18 implements Strategy {

    private final String name;
    private final String symbol;
    private final List<Bar> history = new ArrayList<>();
    private final List<Order> pendingOrders = new ArrayList<>();
    private boolean hasPosition = false;

    private static final int STOP_LOSS_POINTS = 55;
    private static final int TAKE_PROFIT_POINTS = 119;
    private static final double PRICE_SCALE = 0.0001;

    /**
     * Creates a new Top20_Mean_reversion_7_RSI18 strategy for the given symbol.
     *
     * @param symbol the trading symbol (e.g. "EUR_USD")
     */
    public Top20_Mean_reversion_7_RSI18(String symbol) {
        this.name = "Top20_Mean_reversion_7_RSI18";
        this.symbol = symbol;
    }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        history.add(bar);

        if (history.size() < 66) return;

        // --- Compute indicator values ---
        double rsi_18_close = rsi(history, 18);
        double ema_17_close = ema(history, 17, "CLOSE");
        double sma_66_close = sma(history, 66, "CLOSE");

        // --- Previous indicator values ---
        double prevRsi_18_close = rsiPrev(history, 18);
        double prevEma_17_close = emaPrev(history, 17, "CLOSE");
        double prevSma_66_close = smaPrev(history, 66, "CLOSE");

        // --- Entry conditions ---
        if (!hasPosition) {
            boolean entryTriggered = false;
            if (prevRsi_18_close <= prevEma_17_close && rsi_18_close > ema_17_close) {
                entryTriggered = true;
            }

            if (entryTriggered) {
                double price = bar.close();
                Order order = new Order(symbol, Order.Side.BUY, Order.Type.MARKET, 1.0, price);
                if (STOP_LOSS_POINTS > 0) {
                    order.withStopLoss(price - STOP_LOSS_POINTS * PRICE_SCALE);
                }
                if (TAKE_PROFIT_POINTS > 0) {
                    order.withTakeProfit(price + TAKE_PROFIT_POINTS * PRICE_SCALE);
                }
                pendingOrders.add(order);
                hasPosition = true;
            }
        }

        // --- Exit conditions ---
        if (hasPosition) {
            boolean exitTriggered = false;
            if (sma_66_close < prevSma_66_close) {
                exitTriggered = true;
            }

            if (exitTriggered) {
                pendingOrders.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, 1.0, bar.close()));
                hasPosition = false;
            }
        }
    }

    @Override
    public void onTick(double bid, double ask, long volume) {
        // Bar-based strategy; tick data not used
    }

    @Override
    public List<Order> getPendingOrders() {
        List<Order> copy = new ArrayList<>(pendingOrders);
        pendingOrders.clear();
        return copy;
    }

    @Override
    public void reset() {
        history.clear();
        pendingOrders.clear();
        hasPosition = false;
    }

    /**
     * Extracts the named price field from a Bar.
     *
     * @param bar   the bar
     * @param field field name (CLOSE, OPEN, HIGH, LOW)
     * @return the field value
     */
    private static double getFieldValue(Bar bar, String field) {
        return switch (field) {
            case "CLOSE" -> bar.close();
            case "OPEN"  -> bar.open();
            case "HIGH"  -> bar.high();
            case "LOW"   -> bar.low();
            default -> throw new IllegalArgumentException("Unknown field: " + field);
        };
    }

    /**
     * Computes a Simple Moving Average from the bar history.
     */
    private static double sma(List<Bar> data, int period, String field) {
        int n = data.size();
        if (n < period) return 0.0;
        double sum = 0.0;
        for (int i = n - period; i < n; i++) {
            sum += getFieldValue(data.get(i), field);
        }
        return sum / period;
    }

    /**
     * Computes the SMA as of the previous bar (one bar ago).
     */
    private static double smaPrev(List<Bar> data, int period, String field) {
        int n = data.size() - 1;
        if (n < period) return 0.0;
        double sum = 0.0;
        for (int i = n - period; i < n; i++) {
            sum += getFieldValue(data.get(i), field);
        }
        return sum / period;
    }

    /**
     * Computes an Exponential Moving Average from the bar history.
     */
    private static double ema(List<Bar> data, int period, String field) {
        int n = data.size();
        if (n < period) return 0.0;
        double multiplier = 2.0 / (period + 1);
        // Seed with SMA of the first 'period' values
        double sum = 0.0;
        for (int i = 0; i < period; i++) {
            sum += getFieldValue(data.get(i), field);
        }
        double ema = sum / period;
        for (int i = period; i < n; i++) {
            double price = getFieldValue(data.get(i), field);
            ema = (price - ema) * multiplier + ema;
        }
        return ema;
    }

    /**
     * Computes the EMA as of the previous bar.
     */
    private static double emaPrev(List<Bar> data, int period, String field) {
        int n = data.size();
        if (n <= period) return 0.0;
        return ema(data.subList(0, n - 1), period, field);
    }

    /**
     * Computes the Relative Strength Index from the bar history.
     */
    private static double rsi(List<Bar> data, int period) {
        int n = data.size();
        if (n < period + 1) return 50.0;
        double avgGain = 0.0, avgLoss = 0.0;
        for (int i = n - period; i < n; i++) {
            double change = data.get(i).close() - data.get(i - 1).close();
            avgGain += Math.max(change, 0.0);
            avgLoss += Math.max(-change, 0.0);
        }
        avgGain /= period;
        avgLoss /= period;
        if (avgLoss == 0.0) return 100.0;
        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1.0 + rs));
    }

    /**
     * Computes the RSI as of the previous bar.
     */
    private static double rsiPrev(List<Bar> data, int period) {
        int n = data.size();
        if (n <= period + 1) return 50.0;
        return rsi(data.subList(0, n - 1), period);
    }

}
