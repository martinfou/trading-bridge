package com.martinfou.trading.data.ibkr;

import com.martinfou.trading.core.Order;

/**
 * Asynchronous execution notification from IBKR (execDetails / orderStatus callbacks).
 *
 * <p>IBKR fills arrive after {@code placeMarketOrder} returns. The broker layer translates
 * these into {@code BrokerEvent.fill} / {@code BrokerEvent.reject} so the runtime can journal
 * them without fabricating fill prices.
 */
public record IbkrExecution(
    String orderId,
    String execId,
    String clientTag,
    String symbol,
    Order.Side side,
    double quantity,
    double fillPrice,
    String status,
    String errorMessage
) {

    public static IbkrExecution filled(String orderId, String execId, String clientTag, String symbol, Order.Side side,
                                       double quantity, double fillPrice) {
        return new IbkrExecution(orderId, execId, clientTag, symbol, side, quantity, fillPrice, "Filled", null);
    }

    public static IbkrExecution rejected(String orderId, String clientTag, String symbol, String errorMessage) {
        return new IbkrExecution(orderId, null, clientTag, symbol, null, 0.0, 0.0, "Rejected", errorMessage);
    }

    public boolean isFill() {
        return "Filled".equalsIgnoreCase(status);
    }

    public boolean isRejected() {
        return "Rejected".equalsIgnoreCase(status);
    }
}
