package com.martinfou.trading.data.ibkr;

import com.martinfou.trading.core.Order;

/** Open position row from IB Gateway (Story 16.10). */
public record IbkrPositionSnapshot(
    String symbol,
    Order.Side side,
    double quantity,
    double averagePrice
) {}
