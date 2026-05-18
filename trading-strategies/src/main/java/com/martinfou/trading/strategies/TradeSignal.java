package com.martinfou.trading.strategies;

import com.martinfou.trading.core.Order;

public record TradeSignal(
    String pair,
    Order.Side side,
    double entryPrice,
    double stopLoss,
    double takeProfit,
    double quantity,       // micro lots (0.01 = 1000 units)
    int confidence,        // 0-100
    String reason,         // Why this trade
    String catalyst        // What triggers it (news, time, price)
) {
    public boolean isConfident() { return confidence >= 65; }
    
    public String summary() {
        return String.format("[%s] %s %s @ %.5f SL:%.5f TP:%.5f | %d%% | %s",
            pair, side, quantity, entryPrice, stopLoss, takeProfit, confidence, reason);
    }
}
