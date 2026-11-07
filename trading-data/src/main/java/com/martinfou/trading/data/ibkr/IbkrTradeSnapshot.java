package com.martinfou.trading.data.ibkr;

/**
 * Snapshot of a recent execution/trade reported by IBKR TWS / IB Gateway via {@code reqExecutions}.
 */
public record IbkrTradeSnapshot(
    String execId,
    String orderId,
    String symbol,
    String side,
    double quantity,
    double price,
    String time,
    String strategyTag,
    double realizedPnL,
    double commission
) {
}

