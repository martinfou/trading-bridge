package com.martinfou.trading.data.ibkr;

/** Account summary from IB Gateway (Story 16.10). */
public record IbkrAccountSnapshot(
    double balance,
    double equity,
    String currency,
    double unrealizedPnL,
    double initialMargin,
    double maintenanceMargin,
    double availableFunds,
    double buyingPower,
    int dayTradesRemaining,
    boolean pdtRestricted
) {
    public IbkrAccountSnapshot(double balance, double equity, String currency) {
        this(balance, equity, currency, equity - balance, 0.0, 0.0, equity, equity, -1, false);
    }
}
