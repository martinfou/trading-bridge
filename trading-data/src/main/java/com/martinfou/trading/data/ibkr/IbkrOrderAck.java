package com.martinfou.trading.data.ibkr;

import java.util.List;

/**
 * Synchronous acknowledgement of an order placement. {@code accepted} means IB acknowledged the
 * order as working (PreSubmitted/Submitted/Filled), not that it filled. Fills arrive later via
 * {@link IbkrOrderUpdate}.
 *
 * @param brokerOrderId IB order id of the parent order
 * @param childOrderIds IB order ids of bracket children (take-profit first, then stop-loss), may be empty
 * @param status        last IB status seen (e.g. {@code Submitted}); {@code PendingAck} if IB was silent before timeout
 */
public record IbkrOrderAck(
    boolean accepted,
    String brokerOrderId,
    List<String> childOrderIds,
    String status,
    String errorMessage
) {

    public IbkrOrderAck {
        childOrderIds = childOrderIds == null ? List.of() : List.copyOf(childOrderIds);
    }

    public static IbkrOrderAck accepted(String brokerOrderId, List<String> children, String status) {
        return new IbkrOrderAck(true, brokerOrderId, children, status, null);
    }

    public static IbkrOrderAck rejected(String brokerOrderId, String message) {
        return new IbkrOrderAck(false, brokerOrderId, List.of(), "Rejected", message);
    }
}
