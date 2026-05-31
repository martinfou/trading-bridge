package com.martinfou.trading.data.oanda;

import com.martinfou.trading.core.Order;

/** Open position row from OANDA open trades. */
public record OandaPositionSnapshot(
    String instrument,
    Order.Side side,
    double units,
    double averagePrice
) {}
