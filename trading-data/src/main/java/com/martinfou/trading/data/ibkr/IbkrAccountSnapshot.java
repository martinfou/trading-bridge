package com.martinfou.trading.data.ibkr;

/** Account summary from IB Gateway (Story 16.10). */
public record IbkrAccountSnapshot(
    double balance,
    double equity,
    String currency
) {}
