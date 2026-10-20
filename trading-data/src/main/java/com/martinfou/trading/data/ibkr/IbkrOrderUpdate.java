package com.martinfou.trading.data.ibkr;

import com.martinfou.trading.core.Order;

import java.time.Instant;

/**
 * Asynchronous order / session event from IB Gateway.
 *
 * <ul>
 *   <li>{@link Kind#EXECUTION}: one execution (possibly partial); {@code quantity}/{@code price} are this
 *       execution, {@code filledTotal}/{@code avgPrice} cumulative for the order.</li>
 *   <li>{@link Kind#STATUS}: order status change ({@code status} = IB status string).</li>
 *   <li>{@link Kind#CANCELLED}: order cancelled (by user, OCA sibling fill, or IB).</li>
 *   <li>{@link Kind#REJECTED}: order rejected / inactive; {@code message} holds IB's reason.</li>
 *   <li>{@link Kind#COMMISSION}: commission report for {@code executionId}; {@code price} holds the amount.</li>
 *   <li>{@link Kind#CONNECTION}: gateway connectivity change; {@code status} is LOST / RESTORED / CLOSED.</li>
 * </ul>
 *
 * @param clientOrderId the IB {@code orderRef}; bracket children use {@code <parent>-TP} / {@code <parent>-SL}
 */
public record IbkrOrderUpdate(
    Kind kind,
    Instant time,
    String brokerOrderId,
    String clientOrderId,
    String symbol,
    Order.Side side,
    double quantity,
    double price,
    double filledTotal,
    double remaining,
    double avgPrice,
    String status,
    String executionId,
    String message
) {

    public enum Kind { EXECUTION, STATUS, CANCELLED, REJECTED, COMMISSION, CONNECTION }

    public static IbkrOrderUpdate connection(String status, String message) {
        return new IbkrOrderUpdate(Kind.CONNECTION, Instant.now(), null, null, null, null,
            0, 0, 0, 0, 0, status, null, message);
    }
}
