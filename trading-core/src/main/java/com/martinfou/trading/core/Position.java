package com.martinfou.trading.core;

/**
 * Represents an open trading position.
 *
 * <p>P&L is always reported in the account's base currency (USD).
 * For non-USD quote currencies (e.g., JPY for GBP/JPY), the
 * {@code quoteToUsdRate} field handles the conversion automatically.</p>
 */
public class Position {
    private final String symbol;
    private final Order.Side side;
    private double quantity;
    private double entryPrice;
    private double stopLoss;
    private double takeProfit;

    /**
     * Conversion rate from the quote currency to USD.
     * <ul>
     *   <li>USD pairs (EUR/USD, GBP/USD): rate = 1.0 (already in USD)</li>
     *   <li>JPY pairs (GBP/JPY, USD/JPY): rate = current USD/JPY (e.g. 95.0)</li>
     *   <li>Cross pairs (EUR/GBP): rate = GBP/USD (to convert to USD)</li>
     * </ul>
     * Default: 1.0 (all prices already in USD).
     */
    private double quoteToUsdRate = 1.0;

    public Position(String symbol, Order.Side side, double quantity, double entryPrice) {
        this.symbol = symbol;
        this.side = side;
        this.quantity = quantity;
        this.entryPrice = entryPrice;
    }

    /**
     * Sets the conversion rate from quote currency to USD.
     * @param rate quote-to-USD rate (e.g. 95.0 for JPY pairs)
     * @return this position (for chaining)
     */
    public Position withQuoteToUsdRate(double rate) {
        if (rate <= 0) throw new IllegalArgumentException("Rate must be positive, got: " + rate);
        this.quoteToUsdRate = rate;
        return this;
    }

    /**
     * Calculates current P&L in USD, converting from quote currency if needed.
     */
    public double currentPnl(double currentPrice) {
        double rawPnl = side == Order.Side.BUY
            ? (currentPrice - entryPrice) * quantity
            : (entryPrice - currentPrice) * quantity;
        return rawPnl / quoteToUsdRate;
    }

    /**
     * Returns P&L as a percentage of entry value (in USD).
     */
    public double pnlPercent(double currentPrice) {
        double entryValue = entryPrice * quantity / quoteToUsdRate;
        if (entryValue == 0) return 0;
        return currentPnl(currentPrice) / entryValue * 100;
    }

    // ─── Getters ───────────────────────────────────────────────

    public String symbol() { return symbol; }
    public Order.Side side() { return side; }
    public double quantity() { return quantity; }
    public double entryPrice() { return entryPrice; }
    public double stopLoss() { return stopLoss; }
    public double takeProfit() { return takeProfit; }
    public double quoteToUsdRate() { return quoteToUsdRate; }
    public Position withStopLoss(double sl) { this.stopLoss = sl; return this; }
    public Position withTakeProfit(double tp) { this.takeProfit = tp; return this; }

    // ─── Position sizing ───────────────────────────────────────

    public void addQuantity(double qty, double avgPrice) {
        double totalQty = this.quantity + qty;
        this.entryPrice = (this.entryPrice * this.quantity + avgPrice * qty) / totalQty;
        this.quantity = totalQty;
    }

    public double reduceQuantity(double qty) {
        if (qty > quantity) qty = quantity;
        this.quantity -= qty;
        return qty;
    }

    @Override
    public String toString() {
        return String.format("%s %s %.4f@%.5f (rate: %.2f)",
                symbol, side, quantity, entryPrice, quoteToUsdRate);
    }
}
