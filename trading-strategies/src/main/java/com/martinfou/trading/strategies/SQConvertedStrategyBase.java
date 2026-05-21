package com.martinfou.trading.strategies;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import java.util.ArrayList;
import java.util.List;

/**
 * Abstract base for all converted StrategyQuant strategies.
 * <p>
 * Handles the common boilerplate:
 * <ul>
 *   <li>Bar history tracking</li>
 *   <li>Order management (pending orders, active order tracking)</li>
 *   <li>Entry-triggered state (prevents multiple entries)</li>
 *   <li>Bar expiration (removes active order after N bars)</li>
 *   <li>Standard {@link #onTick}, {@link #getPendingOrders}, {@link #reset}</li>
 * </ul>
 * Concrete subclasses override {@link #execute(Bar)} with their entry logic.
 */
public abstract class SQConvertedStrategyBase implements Strategy {

    protected static final double JPY_PIP = 0.01;
    protected static final double FOREX_PIP = 0.0001;

    protected final List<Bar> history = new ArrayList<>();
    protected final List<Order> pendingOrders = new ArrayList<>();
    protected final String symbol;
    protected final double quantity;
    protected final double pip;

    protected Order activeOrder = null;
    protected int barsSinceEntry = 0;
    protected boolean entryTriggered = false;
    protected int expirationBars = 0;
    protected int minBars = 50;

    protected SQConvertedStrategyBase(String symbol, double quantity, double pip) {
        this.symbol = symbol;
        this.quantity = quantity;
        this.pip = pip;
    }

    /** The human-readable strategy name (override to customize). */
    @Override
    public abstract String name();

    /**
     * Called on each new bar. Implement entry logic here.
     * Use {@link #placeBuyStop(double, double, double, int)} to submit orders.
     *
     * @param bar the current bar
     * @param history full bar history (already including the current bar)
     */
    protected abstract void execute(Bar bar);

    @Override
    public void onTick(double bid, double ask, long volume) {
        // Not used for bar-based strategies
    }

    @Override
    public void onBar(Bar bar) {
        history.add(bar);
        if (history.size() < minBars) return;

        // --- Manage active order expiration ---
        if (activeOrder != null) {
            barsSinceEntry++;
            if (expirationBars > 0 && barsSinceEntry >= expirationBars) {
                pendingOrders.remove(activeOrder);
                activeOrder = null;
            }
            return;
        }

        if (entryTriggered) return;

        // Delegate to concrete strategy
        execute(bar);
    }

    @Override
    public List<Order> getPendingOrders() {
        pendingOrders.removeIf(o -> o.status() != Order.Status.PENDING);
        return pendingOrders;
    }

    @Override
    public void reset() {
        history.clear();
        pendingOrders.clear();
        activeOrder = null;
        barsSinceEntry = 0;
        entryTriggered = false;
        expirationBars = 0;
    }

    // ---------------------------------------------------------------
    //  Order placement helpers
    // ---------------------------------------------------------------

    /**
     * Places a BUY STOP order with SL/PT and optional bar expiration.
     *
     * @param entryPrice  stop entry price
     * @param slPips      stop-loss distance in pips
     * @param tpPips      take-profit distance in pips
     * @param expiration  max bars before auto-cancellation (0 = no expiration)
     */
    protected void placeBuyStop(double entryPrice, double slPips, double tpPips, int expiration) {
        placeBuyStop(entryPrice, slPips, tpPips, 0, 0, expiration);
    }

    /**
     * Places a BUY STOP order with full parameters.
     *
     * @param entryPrice      stop entry price
     * @param slPips          stop-loss distance in pips
     * @param tpPips          take-profit distance in pips
     * @param trailActivation pips at which trailing activates (0 = no trailing)
     * @param trailStep       trailing step in pips
     * @param expiration      max bars before auto-cancellation (0 = no expiration)
     */
    protected void placeBuyStop(double entryPrice, double slPips, double tpPips,
                                double trailActivation, double trailStep, int expiration) {
        double sl = slPips > 0 ? entryPrice - (slPips * pip) : 0;
        double tp = tpPips > 0 ? entryPrice + (tpPips * pip) : 0;
        Order order = new Order(symbol, Order.Side.BUY, Order.Type.STOP, quantity, entryPrice)
            .withStopLoss(sl)
            .withTakeProfit(tp);
        pendingOrders.add(order);
        activeOrder = order;
        entryTriggered = true;
        barsSinceEntry = 0;
        expirationBars = expiration;
    }

    // ---------------------------------------------------------------
    //  Convenience accessor
    // ---------------------------------------------------------------

    /** Returns the bar at {@code shift} bars back (0 = current). */
    protected Bar bar(int shift) {
        return history.get(history.size() - 1 - shift);
    }
}
