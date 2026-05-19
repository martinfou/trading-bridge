package com.martinfou.trading.strategies.generated;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import java.util.ArrayList;
import java.util.List;

/**
 * Test123 — auto-generated from a genetic-algorithm chromosome.
 *
 * <p>Entry conditions:
 * <ul>
 *   <li>SMA(50, CLOSE)</li>
 *   <li>ADX(14, CLOSE)</li>
 * </ul>
 * <p>Exit conditions:
 * <ul>
 *   <li>SMA(20, CLOSE)</li>
 * </ul>
 */
public class Test123 implements Strategy {

    private final String name;
    private final String symbol;
    private final List<Bar> history = new ArrayList<>();
    private final List<Order> pendingOrders = new ArrayList<>();
    private boolean hasPosition = false;

    private static final int STOP_LOSS_POINTS = 150;
    private static final int TAKE_PROFIT_POINTS = 300;
    private static final double PRICE_SCALE = 0.0001;

    /**
     * Creates a new Test123 strategy for the given symbol.
     *
     * @param symbol the trading symbol (e.g. "EUR_USD")
     */
    public Test123(String symbol) {
        this.name = "Test123";
        this.symbol = symbol;
    }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        history.add(bar);

        if (history.size() < 50) return;

        // --- Compute indicator values ---
        double sma_50_close = sma(history, 50, "CLOSE");
        double adx_14_close = adx(history, 14);
        double sma_20_close = sma(history, 20, "CLOSE");

        // --- Previous indicator values ---
        double prevSma_50_close = smaPrev(history, 50, "CLOSE");
        double prevAdx_14_close = adxPrev(history, 14);
        double prevSma_20_close = smaPrev(history, 20, "CLOSE");

        // --- Entry conditions ---
        if (!hasPosition) {
            boolean entryTriggered = false;
            if (prevSma_50_close <= prevAdx_14_close && sma_50_close > adx_14_close) {
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
            if (sma_20_close < prevSma_20_close) {
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
     * Computes a simplified ADX (Average Directional Index) from bar history.
     */
    private static double adx(List<Bar> data, int period) {
        int n = data.size();
        if (n < period + 1) return 25.0;
        double sumPlusDM = 0.0, sumMinusDM = 0.0, sumTr = 0.0;
        for (int i = n - period; i < n; i++) {
            Bar cur = data.get(i);
            Bar prev = data.get(i - 1);
            double upMove = cur.high() - prev.high();
            double downMove = prev.low() - cur.low();
            double plusDM = (upMove > downMove && upMove > 0) ? upMove : 0.0;
            double minusDM = (downMove > upMove && downMove > 0) ? downMove : 0.0;
            double tr = Math.max(cur.high() - cur.low(),
                Math.max(Math.abs(cur.high() - prev.close()),
                    Math.abs(cur.low() - prev.close())));
            sumPlusDM += plusDM;
            sumMinusDM += minusDM;
            sumTr += tr;
        }
        if (sumTr == 0.0) return 25.0;
        double pdi = 100.0 * sumPlusDM / sumTr;
        double mdi = 100.0 * sumMinusDM / sumTr;
        double dx = Math.abs(pdi - mdi) / (pdi + mdi) * 100.0;
        return dx;
    }

    /**
     * Computes the ADX as of the previous bar.
     */
    private static double adxPrev(List<Bar> data, int period) {
        int n = data.size();
        if (n <= period + 1) return 25.0;
        return adx(data.subList(0, n - 1), period);
    }

}
