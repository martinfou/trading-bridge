package com.martinfou.trading.strategies.generated;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import java.util.ArrayList;
import java.util.List;

/**
 * Top2_Trend_following_97_EMA10 — auto-generated from a genetic-algorithm chromosome.
 *
 * <p>Entry conditions:
 * <ul>
 *   <li>EMA(10, CLOSE)</li>
 *   <li>EMA(19, CLOSE)</li>
 * </ul>
 * <p>Exit conditions:
 * <ul>
 *   <li>SMA(17, CLOSE)</li>
 * </ul>
 */
public class Top2_Trend_following_97_EMA10 implements Strategy {

    private final String name;
    private final String symbol;
    private final List<Bar> history = new ArrayList<>();
    private final List<Order> pendingOrders = new ArrayList<>();
    private boolean hasPosition = false;

    private static final int STOP_LOSS_POINTS = 163;
    private static final int TAKE_PROFIT_POINTS = 263;
    private static final double PRICE_SCALE = 0.0001;

    /**
     * Creates a new Top2_Trend_following_97_EMA10 strategy for the given symbol.
     *
     * @param symbol the trading symbol (e.g. "EUR_USD")
     */
    public Top2_Trend_following_97_EMA10(String symbol) {
        this.name = "Top2_Trend_following_97_EMA10";
        this.symbol = symbol;
    }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        history.add(bar);

        if (history.size() < 19) return;

        // --- Compute indicator values ---
        double ema_10_close = ema(history, 10, "CLOSE");
        double ema_19_close = ema(history, 19, "CLOSE");
        double sma_17_close = sma(history, 17, "CLOSE");

        // --- Previous indicator values ---
        double prevEma_10_close = emaPrev(history, 10, "CLOSE");
        double prevEma_19_close = emaPrev(history, 19, "CLOSE");
        double prevSma_17_close = smaPrev(history, 17, "CLOSE");

        // --- Entry conditions ---
        if (!hasPosition) {
            boolean entryTriggered = false;
            if (prevEma_10_close <= prevEma_19_close && ema_10_close > ema_19_close) {
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
            if (sma_17_close < prevSma_17_close) {
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

}
