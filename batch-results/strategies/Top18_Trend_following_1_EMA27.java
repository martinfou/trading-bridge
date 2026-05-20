package com.martinfou.trading.strategies.generated;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import java.util.ArrayList;
import java.util.List;

/**
 * Top18_Trend_following_1_EMA27 — auto-generated from a genetic-algorithm chromosome.
 *
 * <p>Entry conditions:
 * <ul>
 *   <li>EMA(27, CLOSE)</li>
 *   <li>ADX(10, CLOSE)</li>
 * </ul>
 * <p>Exit conditions:
 * <ul>
 *   <li>EMA(41, CLOSE)</li>
 * </ul>
 */
public class Top18_Trend_following_1_EMA27 implements Strategy {

    private final String name;
    private final String symbol;
    private final List<Bar> history = new ArrayList<>();
    private final List<Order> pendingOrders = new ArrayList<>();
    private boolean hasPosition = false;

    private static final int STOP_LOSS_POINTS = 61;
    private static final int TAKE_PROFIT_POINTS = 320;
    private static final double PRICE_SCALE = 0.0001;

    /**
     * Creates a new Top18_Trend_following_1_EMA27 strategy for the given symbol.
     *
     * @param symbol the trading symbol (e.g. "EUR_USD")
     */
    public Top18_Trend_following_1_EMA27(String symbol) {
        this.name = "Top18_Trend_following_1_EMA27";
        this.symbol = symbol;
    }

    @Override
    public String name() { return name; }

    @Override
    public void onBar(Bar bar) {
        history.add(bar);

        if (history.size() < 41) return;

        // --- Compute indicator values ---
        double ema_27_close = ema(history, 27, "CLOSE");
        double adx_10_close = adx(history, 10);
        double ema_41_close = ema(history, 41, "CLOSE");

        // --- Previous indicator values ---
        double prevEma_27_close = emaPrev(history, 27, "CLOSE");
        double prevAdx_10_close = adxPrev(history, 10);
        double prevEma_41_close = emaPrev(history, 41, "CLOSE");

        // --- Entry conditions ---
        if (!hasPosition) {
            boolean entryTriggered = false;
            if (prevEma_27_close <= prevAdx_10_close && ema_27_close > adx_10_close) {
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
            if (ema_41_close < prevEma_41_close) {
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
