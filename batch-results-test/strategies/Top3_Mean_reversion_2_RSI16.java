package com.martinfou.trading.strategies.generated;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import java.util.ArrayList;
import java.util.List;

/**
 * Top3_Mean_reversion_2_RSI16 — auto-generated from a genetic-algorithm chromosome.
 *
 * <p>Entry conditions:
 * <ul>
 *   <li>RSI(16, CLOSE)</li>
 *   <li>SMA(67, CLOSE)</li>
 * </ul>
 * <p>Exit conditions:
 * <ul>
 *   <li>RSI(16, CLOSE)</li>
 * </ul>
 */
public class Top3_Mean_reversion_2_RSI16 implements Strategy {

    private final String name;
    private final String symbol;
    private final List<Bar> history = new ArrayList<>();
    private final List<Order> pendingOrders = new ArrayList<>();
    private boolean hasPosition = false;

    private static final int STOP_LOSS_POINTS = 98;
    private static final int TAKE_PROFIT_POINTS = 125;
    private static final double PRICE_SCALE = 0.0001;

    /**
     * Creates a new Top3_Mean_reversion_2_RSI16 strategy for the given symbol.
     *
     * @param symbol the trading symbol (e.g. "EUR_USD")
     */
    public Top3_Mean_reversion_2_RSI16(String symbol) {
        this.name = "Top3_Mean_reversion_2_RSI16";
        this.symbol = symbol;
    }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        history.add(bar);

        if (history.size() < 67) return;

        // --- Compute indicator values ---
        double rsi_16_close = rsi(history, 16);
        double sma_67_close = sma(history, 67, "CLOSE");

        // --- Previous indicator values ---
        double prevRsi_16_close = rsiPrev(history, 16);
        double prevSma_67_close = smaPrev(history, 67, "CLOSE");

        // --- Entry conditions ---
        if (!hasPosition) {
            boolean entryTriggered = false;
            if (prevRsi_16_close <= prevSma_67_close && rsi_16_close > sma_67_close) {
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
            if (rsi_16_close > 70.0) {
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
