package com.martinfou.trading.broker;

/** Snapshot of broker account balances and margin utilization. */
public record AccountState(
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
    public AccountState(double balance, double equity, String currency) {
        this(balance, equity, currency, equity - balance, 0.0, 0.0, equity, equity, -1, false);
    }
}
