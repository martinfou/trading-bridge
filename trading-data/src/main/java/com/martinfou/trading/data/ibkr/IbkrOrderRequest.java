package com.martinfou.trading.data.ibkr;

import com.martinfou.trading.core.Order;

/**
 * Broker-neutral order request for IB Gateway: MARKET / LIMIT / STOP with an optional native
 * bracket (take-profit LMT + stop-loss STP children, OCA-linked).
 *
 * @param clientOrderId trading-bridge order id; sent as IB {@code orderRef} for idempotency and reconciliation
 * @param limitPrice    LIMIT price (ignored otherwise)
 * @param stopPrice     STOP trigger price (ignored otherwise)
 * @param stopLoss      bracket stop-loss trigger, {@code <= 0} for none
 * @param takeProfit    bracket take-profit limit, {@code <= 0} for none
 */
public record IbkrOrderRequest(
    String clientOrderId,
    String symbol,
    Order.Side side,
    Order.Type type,
    double quantity,
    double limitPrice,
    double stopPrice,
    double stopLoss,
    double takeProfit,
    boolean outsideRth
) {

    public IbkrOrderRequest {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol is required");
        }
        if (side == null || type == null) {
            throw new IllegalArgumentException("side and type are required");
        }
        if (!(quantity > 0) || Double.isInfinite(quantity)) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        if (type == Order.Type.LIMIT && !(limitPrice > 0)) {
            throw new IllegalArgumentException("LIMIT order requires a positive limit price");
        }
        if (type == Order.Type.STOP && !(stopPrice > 0)) {
            throw new IllegalArgumentException("STOP order requires a positive stop price");
        }
    }

    public static IbkrOrderRequest market(String clientOrderId, String symbol, Order.Side side, double quantity) {
        return new IbkrOrderRequest(clientOrderId, symbol, side, Order.Type.MARKET, quantity, 0, 0, 0, 0, false);
    }

    /** Maps a core {@link Order}: {@code price} is the limit price for LIMIT and the trigger for STOP. */
    public static IbkrOrderRequest from(Order order) {
        double limit = order.type() == Order.Type.LIMIT ? order.price() : 0;
        double stop = order.type() == Order.Type.STOP ? order.price() : 0;
        return new IbkrOrderRequest(
            order.id(), order.symbol(), order.side(), order.type(), order.quantity(),
            limit, stop, order.stopLoss(), order.takeProfit(), false);
    }

    public boolean hasBracket() {
        return stopLoss > 0 || takeProfit > 0;
    }
}
