package com.martinfou.trading.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AssetValuationTest {

    @Test
    void testForexValuationEURUSD() {
        AssetValuationModel model = AssetValuationRegistry.resolve("EUR_USD");
        assertEquals(AssetClass.FOREX, model.assetClass());

        // BUY EUR/USD from 1.0500 to 1.0550 for 10,000 units -> +50.0 USD
        double pnlBuy = model.calculatePnL(Order.Side.BUY, 1.0500, 1.0550, 10_000, 150.0);
        assertEquals(50.0, pnlBuy, 1e-6);

        // SELL EUR/USD from 1.0550 to 1.0500 for 10,000 units -> +50.0 USD
        double pnlSell = model.calculatePnL(Order.Side.SELL, 1.0550, 1.0500, 10_000, 150.0);
        assertEquals(50.0, pnlSell, 1e-6);
    }

    @Test
    void testFuturesValuationMES() {
        AssetValuationModel model = AssetValuationRegistry.resolve("MES");
        assertEquals(AssetClass.FUTURES, model.assetClass());

        // Micro S&P 500 multiplier = 5.0
        // BUY 2 contracts MES from 5000.00 to 5010.00 (+10 pts) -> 10 pts * 5 $/pt * 2 contracts = $100.00
        double pnlBuy = model.calculatePnL(Order.Side.BUY, 5000.00, 5010.00, 2.0, 150.0);
        assertEquals(100.0, pnlBuy, 1e-6);

        // Notional value: 5000 * 5.0 * 2 = $50,000
        assertEquals(50000.0, model.notionalValue(5000.00, 2.0), 1e-6);

        // Enforces integer quantity
        assertEquals(2.0, model.validateQuantity(2.4));
        assertEquals(1.0, model.validateQuantity(0.2));
    }

    @Test
    void testFuturesValuationM2K() {
        AssetValuationModel model = AssetValuationRegistry.resolve("M2K");
        assertEquals(AssetClass.FUTURES, model.assetClass());

        // Micro Russell 2000 multiplier = 5.0
        // BUY 1 contract M2K from 2000.00 to 2005.00 (+5 pts) -> 5 * 5 * 1 = $25.00
        double pnl = model.calculatePnL(Order.Side.BUY, 2000.00, 2005.00, 1.0, 150.0);
        assertEquals(25.0, pnl, 1e-6);
    }

    @Test
    void testFuturesValuationEMD() {
        AssetValuationModel model = AssetValuationRegistry.resolve("EMD");
        assertEquals(AssetClass.FUTURES, model.assetClass());

        // E-mini S&P MidCap 400 multiplier = 100.0
        // BUY 1 contract EMD from 3000.00 to 3002.00 (+2 pts) -> 2 * 100 * 1 = $200.00
        double pnl = model.calculatePnL(Order.Side.BUY, 3000.00, 3002.00, 1.0, 150.0);
        assertEquals(200.0, pnl, 1e-6);
    }

    @Test
    void testStockValuationIWM() {
        AssetValuationModel model = AssetValuationRegistry.resolve("IWM");
        assertEquals(AssetClass.EQUITY, model.assetClass());

        // BUY 100 shares IWM from 200.00 to 205.00 -> +$500.00
        double pnl = model.calculatePnL(Order.Side.BUY, 200.00, 205.00, 100.0, 150.0);
        assertEquals(500.0, pnl, 1e-6);

        // Notional value: 200.00 * 100 = $20,000
        assertEquals(20000.0, model.notionalValue(200.00, 100.0), 1e-6);
    }
}
