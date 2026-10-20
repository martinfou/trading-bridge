package com.martinfou.trading.data.ibkr;

import com.martinfou.trading.core.Order;

/** Working order as reported by IB Gateway ({@code reqOpenOrders}). */
public record IbkrOpenOrder(
    String brokerOrderId,
    String clientOrderId,
    String symbol,
    Order.Side side,
    String orderType,
    double quantity,
    double limitPrice,
    double auxPrice,
    String parentId,
    String status
) {}
