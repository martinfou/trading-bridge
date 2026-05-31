package com.martinfou.trading.parser.actions;

import com.martinfou.trading.core.Order;

import java.util.Optional;

/** Parsed {@code EnterAtStop} action (story 2-8). */
public record SqOrderIntent(
    String actionKey,
    Order.Side side,
    double quantity,
    Optional<Double> stopPrice,
    Optional<Integer> stopLossPips,
    Optional<Integer> profitTargetPips,
    Optional<Integer> barsValid
) {
    public boolean isComplete() {
        return stopPrice.isPresent() && quantity > 0;
    }

    public Optional<Order> toOrder(String symbol) {
        if (!isComplete()) {
            return Optional.empty();
        }
        return Optional.of(new Order(symbol, side, Order.Type.STOP, quantity, stopPrice.get()));
    }
}
