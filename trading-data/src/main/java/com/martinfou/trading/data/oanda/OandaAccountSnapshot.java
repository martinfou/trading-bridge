package com.martinfou.trading.data.oanda;

/** OANDA account summary snapshot. */
public record OandaAccountSnapshot(
    double balance, 
    double nav, 
    double unrealizedPl, 
    String currency,
    double marginAvailable,
    double marginUsed,
    double marginCloseoutPercent
) {}
