package com.martinfou.trading.strategies.llmweekly;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;

import java.util.List;

/** T8 — explicit no-trade week strategy (Epic 22.5). */
public class NoTradeWeekStrategy implements Strategy {

    private final String strategyName;
    private final String reason;

    public NoTradeWeekStrategy(String strategyName, String reason) {
        this.strategyName = strategyName;
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }

    @Override
    public String name() {
        return strategyName;
    }

    @Override
    public void onBar(Bar bar) {
        // intentional no-op
    }

    @Override
    public void onTick(double bid, double ask, long volume) {
        // intentional no-op
    }

    @Override
    public List<Order> getPendingOrders() {
        return List.of();
    }

    @Override
    public void reset() {
        // no state
    }
}
