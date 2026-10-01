package com.martinfou.trading.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The story 1.4 predicate lives ONCE here ({@link Order#hasProtectiveStop()}) so the live path and
 * the (deferred) backtest mirror can never diverge on the "no stop ⇒ no entry" rule.
 */
class OrderHasProtectiveStopTest {

    @Test
    @DisplayName("hasProtectiveStop is true exactly when the order carries a stop")
    void hasProtectiveStopReflectsStopPresence() {
        Order noStop = new Order("EUR_USD", Order.Side.BUY, Order.Type.MARKET, 1000, 1.1000);
        assertFalse(noStop.hasProtectiveStop(), "a bare order carries no protective stop");

        assertTrue(noStop.withStopLoss(1.0900).hasProtectiveStop(),
            "a BUY stop below entry is protective");

        Order sell = new Order("EUR_USD", Order.Side.SELL, Order.Type.MARKET, 1000, 1.1000);
        assertTrue(sell.withStopLoss(1.1100).hasProtectiveStop(),
            "a SELL stop above entry is protective");
    }
}
