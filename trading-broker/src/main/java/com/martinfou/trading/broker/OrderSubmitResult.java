package com.martinfou.trading.broker;

/** Result of {@link Broker#submitOrder(Order)}. */
public record OrderSubmitResult(boolean accepted, String brokerOrderId, String rejectReason) {

    public static OrderSubmitResult filled(String brokerOrderId) {
        return new OrderSubmitResult(true, brokerOrderId, null);
    }

    public static OrderSubmitResult rejected(String reason) {
        return new OrderSubmitResult(false, null, reason);
    }
}
