package com.martinfou.trading.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ForexPnLTest {

    @Test
    void eurUsdPnlIsDirectQuote() {
        double pnl = ForexPnL.pnlUsd("EUR_USD", Order.Side.BUY, 1.05, 1.06, 1_000);
        assertEquals(10.0, pnl, 1e-9);
    }

    @Test
    void gbpJpyPnlConvertsThroughUsdJpy() {
        double pnl = ForexPnL.pnlUsd("GBP_JPY", Order.Side.BUY, 200.0, 203.0, 1_000, 150.0);
        assertEquals(20.0, pnl, 1e-9);
    }

    @Test
    void usdJpyPnlDividesByExitRate() {
        double pnl = ForexPnL.pnlUsd("USD_JPY", Order.Side.BUY, 150.0, 151.0, 1_000);
        assertEquals(1_000.0 / 151.0, pnl, 1e-6);
    }
}
