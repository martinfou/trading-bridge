package com.martinfou.trading.strategies.weekly;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.core.TimeConventions;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One-shot weekly strategy generated from Saturday analysis.
 *
 * <p>Places a single STOP order at the configured entry price, then manages
 * the position with fixed SL/TP. Expires automatically after {@code maxBars}
 * bars or at Friday 23:00 ET (whichever comes first).</p>
 *
 * <p>This class is thread-safe for read operations once constructed.</p>
 *
 * <h3>Stop order semantics</h3>
 * <ul>
 *   <li>{@code BUYSTOP} = {@code Order.Side.BUY} with {@code Order.Type.STOP}
 *       — triggers when the market rises to (or above) {@code entryPrice}.</li>
 *   <li>{@code SELLSTOP} = {@code Order.Side.SELL} with {@code Order.Type.STOP}
 *       — triggers when the market falls to (or below) {@code entryPrice}.</li>
 * </ul>
 */
public final class WeeklyStrategy implements Strategy {

    public enum StopOrderType {
        BUYSTOP,
        SELLSTOP
    }

    private final String name;
    private final String symbol;
    private final Order.Side side;
    private final StopOrderType orderType;
    private final double entryPrice;
    private final double stopLoss;
    private final double takeProfit;
    private final double quantity;
    private final int maxBars;
    private final String reason;
    private final int confidence;

    private final List<Order> pendingOrders = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();

    private boolean orderPlaced;
    private boolean hasPosition;
    private boolean expired;
    private int barsSinceOpen;

    /**
     * Constructs a weekly strategy with the given parameters.
     *
     * @param name       human-readable name (e.g. "EURUSD_Breakout")
     * @param symbol     OANDA symbol (e.g. "EUR_USD")
     * @param side       position side (BUY or SELL)
     * @param orderType  stop order type (BUYSTOP or SELLSTOP)
     * @param entryPrice price at which the stop order sits
     * @param stopLoss   stop-loss price
     * @param takeProfit take-profit price
     * @param quantity   trade size in units
     * @param maxBars    max bars before auto-expiry (e.g. 120 for 5m bars → 10h)
     * @param reason     human-readable trade rationale
     * @param confidence analyst confidence (0–100)
     */
    public WeeklyStrategy(String name, String symbol, Order.Side side, StopOrderType orderType,
                          double entryPrice, double stopLoss, double takeProfit,
                          double quantity, int maxBars, String reason, int confidence) {
        this.name = Objects.requireNonNull(name, "name");
        this.symbol = Objects.requireNonNull(symbol, "symbol");
        this.side = Objects.requireNonNull(side, "side");
        this.orderType = Objects.requireNonNull(orderType, "orderType");
        this.entryPrice = entryPrice;
        this.stopLoss = stopLoss;
        this.takeProfit = takeProfit;
        this.quantity = quantity;
        this.maxBars = maxBars;
        this.reason = Objects.requireNonNullElse(reason, "");
        this.confidence = confidence;
    }

    @Override
    public String name() { return name; }

    public String symbol() { return symbol; }
    public Order.Side side() { return side; }
    public StopOrderType orderType() { return orderType; }
    public double entryPrice() { return entryPrice; }
    public double stopLoss() { return stopLoss; }
    public double takeProfit() { return takeProfit; }
    public double quantity() { return quantity; }
    public int maxBars() { return maxBars; }
    public String reason() { return reason; }
    public int confidence() { return confidence; }
    public boolean isExpired() { return expired; }

    @Override
    public void onBar(Bar bar) {
        if (expired) return;

        // Check for Friday 23:00 ET expiry
        if (isPastFriday2300(bar.timestamp())) {
            expired = true;
            pendingOrders.clear();
            return;
        }

        history.add(bar);

        // Place the initial stop order on the first bar
        if (!orderPlaced && history.size() >= 1) {
            Order order = new Order(symbol, side, Order.Type.STOP, quantity, entryPrice);
            order.withStopLoss(stopLoss).withTakeProfit(takeProfit);
            pendingOrders.add(order);
            orderPlaced = true;
        }

        // Track bars since order was placed
        if (orderPlaced && !hasPosition) {
            barsSinceOpen++;
            if (barsSinceOpen >= maxBars) {
                expired = true;
                pendingOrders.clear();
                return;
            }
        }

        // Check if our stop order should trigger (simulated on bar data)
        // Note: In a real engine, the Order.Type.STOP would be processed
        // by the backtest engine. Here we simulate it for single-bar context.
        if (orderPlaced && !hasPosition) {
            boolean triggered = switch (orderType) {
                case BUYSTOP  -> bar.high() >= entryPrice;
                case SELLSTOP -> bar.low()  <= entryPrice;
            };
            if (triggered && !expired) {
                hasPosition = true;
            }
        }

        // In a real backtest, the engine manages SL/TP hit.
        // This strategy simply reports the expected SL/TP on the order.
    }

    @Override
    public void onTick(double bid, double ask, long volume) {
        // Tick-level stop order simulation would go here in a real engine.
        // Bar-based is sufficient for weekly analysis strategies.
    }

    @Override
    public List<Order> getPendingOrders() {
        if (expired) return List.of();
        List<Order> copy = new ArrayList<>(pendingOrders);
        pendingOrders.clear();
        return copy;
    }

    @Override
    public void reset() {
        history.clear();
        pendingOrders.clear();
        orderPlaced = false;
        hasPosition = false;
        expired = false;
        barsSinceOpen = 0;
    }

    // ─── Helpers ───────────────────────────────────────────

    /**
     * Returns true if the given timestamp is at or past Friday 23:00 ET.
     */
    private static boolean isPastFriday2300(Instant instant) {
        ZonedDateTime zdt = instant.atZone(TimeConventions.DISPLAY_ZONE);
        if (zdt.getDayOfWeek().compareTo(DayOfWeek.FRIDAY) > 0) {
            // Saturday or Sunday → definitely past
            return true;
        }
        if (zdt.getDayOfWeek() == DayOfWeek.FRIDAY) {
            return zdt.getHour() >= 23;
        }
        return false;
    }

    @Override
    public String toString() {
        return String.format("WeeklyStrategy[%s %s %s entry=%.5f SL=%.5f TP=%.5f qty=%.2f bars=%d]",
                name, symbol, orderType, entryPrice, stopLoss, takeProfit, quantity, maxBars);
    }
}
