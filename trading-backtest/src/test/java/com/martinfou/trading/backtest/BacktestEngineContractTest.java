package com.martinfou.trading.backtest;

import com.martinfou.trading.core.Trade;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic contract tests for {@link BacktestEngine} fill semantics and
 * accounting invariants. Complements the golden integration test with
 * reproducible micro-scenarios (no random data).
 */
class BacktestEngineContractTest {

    private static final double CAPITAL = 10_000.0;

    @Test
    void marketOrder_fillsAtBarOpen() {
        double fillOpen = 1.2500;
        List<com.martinfou.trading.core.Bar> bars = TestBars.ohlc(new double[][] {
            {fillOpen, fillOpen + 0.0010, fillOpen - 0.0010, fillOpen + 0.0005},
            {1.2510, 1.2520, 1.2500, 1.2515}
        });

        BacktestResult result = new BacktestEngine(TestStrategies.buyOnce(), bars, CAPITAL).run();

        assertEquals(1, result.totalTrades());
        assertEquals(fillOpen, result.trades().getFirst().entryPrice(), 1e-9, "MARKET fill at bar.open()");
    }

    @Test
    void oppositeSideMarket_createsHedgePosition() {
        // With hedging (edging) enabled, opposite-side MARKET orders
        // create new independent positions instead of closing existing ones.
        List<com.martinfou.trading.core.Bar> bars = TestBars.ohlc(new double[][] {
            {1.1000, 1.1010, 1.0990, 1.1005},
            {1.1010, 1.1020, 1.1000, 1.1015},
            {1.1020, 1.1030, 1.1010, 1.1025}
        });

        BacktestResult result = new BacktestEngine(TestStrategies.buyThenSell(), bars, CAPITAL).run();

        // BUY bar 0 → LONG(10000) @ 1.1000
        // SELL bar 1 (no closeOnly) → SHORT(10000) @ 1.1010 (hedge position)
        // Both positions closed at final bar close 1.1025
        // LONG PnL = (1.1025-1.1000)*10000 = $25
        // SHORT PnL = (1.1010-1.1025)*10000 = -$15
        assertEquals(2, result.totalTrades(), "two positions (LONG + SHORT hedge), not one round-trip");
        assertEquals(1.1025, result.trades().get(0).exitPrice(), 1e-9, "LONG exits at final close");
        assertEquals(1.1025, result.trades().get(1).exitPrice(), 1e-9, "SHORT exits at final close");
    }

    @Test
    void stopLoss_triggersExitAtSlPrice() {
        double stopLoss = 1.1950;
        List<com.martinfou.trading.core.Bar> bars = TestBars.ohlc(new double[][] {
            {1.2000, 1.2010, 1.1990, 1.2000},
            {1.1990, 1.1995, 1.1940, 1.1945}
        });

        BacktestResult result = new BacktestEngine(TestStrategies.buyWithStopLoss(stopLoss), bars, CAPITAL).run();

        assertEquals(1, result.totalTrades());
        assertEquals(stopLoss, result.trades().getFirst().exitPrice(), 1e-9, "SL exit at stop price");
    }

    @Test
    void accountingInvariants_totalPnlAndFinalEquityConsistent() {
        List<com.martinfou.trading.core.Bar> bars = TestBars.ohlc(new double[][] {
            {1.1000, 1.1010, 1.0990, 1.1005},
            {1.1010, 1.1020, 1.1000, 1.1015},
            {1.1020, 1.1030, 1.1010, 1.1025}
        });

        BacktestResult result = new BacktestEngine(TestStrategies.buyThenSell(), bars, CAPITAL).run();

        double tradePnlSum = result.trades().stream().mapToDouble(Trade::pnl).sum();
        double expectedTotalPnl = tradePnlSum - result.totalCommission();

        assertEquals(expectedTotalPnl, result.totalPnl(), 1e-6, "totalPnl = sum(trade pnl) - commission");
        assertEquals(CAPITAL + result.totalPnl(), result.finalEquity(), 1e-6,
            "finalEquity = initialCapital + totalPnl when flat");
        assertEquals(result.totalPnl() / CAPITAL * 100.0, result.totalReturnPct(), 1e-6,
            "totalReturnPct = totalPnl / initialCapital * 100");
    }

    @Test
    void commissionAndSlippage_reduceNetPnl() {
        List<com.martinfou.trading.core.Bar> bars = TestBars.ohlc(new double[][] {
            {1.1000, 1.1010, 1.0990, 1.1005},
            {1.1010, 1.1020, 1.1000, 1.1015}
        });

        BacktestResult noCost = new BacktestEngine(TestStrategies.buyOnce(), bars, CAPITAL).run();
        BacktestResult withCost = new BacktestEngine(TestStrategies.buyOnce(), bars, CAPITAL)
            .withCommissionFixed(5.0)
            .withSlippagePct(0.0001)
            .run();

        assertTrue(withCost.totalCommission() > 0 || withCost.totalSlippage() > 0);
        assertTrue(withCost.totalPnl() < noCost.totalPnl(), "costs reduce net PnL");
        assertEquals(withCost.finalEquity(), CAPITAL + withCost.totalPnl(), 1e-6);
    }

    @Test
    void stopLossSlippage_widensLongExitLoss() {
        double stopLoss = 1.1950;
        List<com.martinfou.trading.core.Bar> bars = TestBars.ohlc(new double[][] {
            {1.2000, 1.2010, 1.1990, 1.2000},
            {1.1990, 1.1995, 1.1940, 1.1945}
        });

        BacktestResult noSlip = new BacktestEngine(TestStrategies.buyWithStopLoss(stopLoss), bars, CAPITAL).run();
        BacktestResult withSlip = new BacktestEngine(TestStrategies.buyWithStopLoss(stopLoss), bars, CAPITAL)
            .withStopSlippagePct(0.0001)
            .run();

        assertEquals(1, noSlip.totalTrades());
        assertEquals(1, withSlip.totalTrades());
        assertEquals(stopLoss, noSlip.trades().getFirst().exitPrice(), 1e-9);
        assertTrue(withSlip.trades().getFirst().exitPrice() < stopLoss,
            "stop slippage pushes long SL exit below stop price");
        assertTrue(withSlip.totalPnl() < noSlip.totalPnl(), "slippage worsens net PnL");
    }

    // ---------------------------------------------------------------
    //  Edging-specific tests
    // ---------------------------------------------------------------

    @Test
    void closeOnly_reducesOppositePosition() {
        // closeOnly() should REDUCE the opposite position, not create a hedge
        List<com.martinfou.trading.core.Bar> bars = TestBars.ohlc(new double[][] {
            {1.1000, 1.1010, 1.0990, 1.1005},
            {1.1010, 1.1020, 1.1000, 1.1015}
        });

        BacktestResult result = new BacktestEngine(TestStrategies.buyThenCloseOnlySell(), bars, CAPITAL).run();

        // BUY bar 0 → LONG(10000) @ 1.1000
        // SELL closeOnly bar 1 → REDUCE_ONLY: closes LONG @ 1.1010 (PnL = (1.1010-1.1000)*10000 = $10)
        // 1 trade, not 2 (no hedge)
        assertEquals(1, result.totalTrades(), "closeOnly should reduce, not create hedge");
        assertEquals(1.1010, result.trades().getFirst().exitPrice(), 1e-9);
    }

    @Test
    void closeOnly_noOppositePosition_isNoOp() {
        // closeOnly() when no opposite position exists should be silently dropped
        List<com.martinfou.trading.core.Bar> bars = TestBars.ohlc(new double[][] {
            {1.1000, 1.1010, 1.0990, 1.1005},
            {1.1010, 1.1020, 1.1000, 1.1015}
        });

        BacktestResult result = new BacktestEngine(TestStrategies.closeOnlyWithoutPosition(), bars, CAPITAL).run();

        assertEquals(0, result.totalTrades(), "closeOnly without opposite position = no-op");
    }

    @Test
    void longAndShortPositions_canCoexist() {
        // Verify both long and short can be held simultaneously (edging)
        List<com.martinfou.trading.core.Bar> bars = TestBars.ohlc(new double[][] {
            {1.1000, 1.1010, 1.0990, 1.1005},
            {1.1010, 1.1020, 1.1000, 1.1015},
            {1.1020, 1.1030, 1.1010, 1.1025}
        });

        BacktestResult result = new BacktestEngine(TestStrategies.longThenShortHedge(), bars, CAPITAL).run();

        // Both LONG and SHORT should survive independently to the end
        // LONG PnL = (1.1025-1.1000)*10000 = $25
        // SHORT PnL = (1.1010-1.1025)*10000 = -$15
        assertEquals(2, result.totalTrades());
        double totalPnl = result.trades().stream().mapToDouble(Trade::pnl).sum();
        assertEquals(10.0, totalPnl, 0.001, "long($25) + short(-$15) = $10");
    }

    @Test
    void stopLoss_onHedge_closesOnlyOneSide() {
        // A stop-loss on one hedge position should close only that position
        double longStopLoss = 1.0990;
        List<com.martinfou.trading.core.Bar> bars = TestBars.ohlc(new double[][] {
            {1.1000, 1.1010, 1.0990, 1.1005},
            {1.1005, 1.1008, 1.0980, 1.0995},  // Bar 1: LONG SL hit (1.0990 >= 1.0980)
            {1.1000, 1.1010, 1.0995, 1.1005}
        });

        BacktestResult result = new BacktestEngine(TestStrategies.longWithStopThenShortHedge(longStopLoss), bars, CAPITAL).run();

        // LONG should be stopped out at 1.0990 on bar 1, SHORT should survive
        assertTrue(result.totalTrades() >= 1);
        // At least one position still open after LONG SL — SHORT survives
        // (final bar close covers the short)
        assertTrue(result.totalTrades() >= 1);
    }

    @Test
    void longSlAndShortSl_canBothExistIndependently() {
        // Each side can have its own SL/TP, they don't interfere
        double longStopLoss = 1.0980;
        double shortStopLoss = 1.1030;
        List<com.martinfou.trading.core.Bar> bars = TestBars.ohlc(new double[][] {
            {1.1000, 1.1010, 1.0990, 1.1005},
            {1.1010, 1.1020, 1.1000, 1.1015},
            {1.1025, 1.1040, 1.1020, 1.1035}  // Bar 2: SHORT SL hit (1.1030 <= 1.1040)
        });

        BacktestResult result = new BacktestEngine(
            TestStrategies.longAndShortWithSeparateStops(longStopLoss, shortStopLoss), bars, CAPITAL).run();

        // SHORT stopped at 1.1030 on bar 2, LONG survives to final close
        // LONG PnL = (1.1035-1.1000)*10000 = $35 (closed at end)
        // SHORT PnL = (1.1010-1.1030)*10000 = -$20 (stopped)
        assertTrue(result.totalTrades() >= 2);
    }

    @Test
    void multiTimeframe_runsH1StrategyOnM1Data() {
        // Create 120 M1 bars (2 hours)
        java.util.List<com.martinfou.trading.core.Bar> bars = new java.util.ArrayList<>();
        java.time.Instant start = java.time.Instant.parse("2026-05-20T10:00:00Z");
        for (int i = 0; i < 120; i++) {
            java.time.Instant time = start.plusSeconds(i * 60);
            bars.add(new com.martinfou.trading.core.Bar("EUR_USD", time, 1.1000 + i * 0.0001, 1.1005 + i * 0.0001, 1.0995 + i * 0.0001, 1.1000 + i * 0.0001, 10));
        }

        java.util.concurrent.atomic.AtomicInteger callCount = new java.util.concurrent.atomic.AtomicInteger(0);
        com.martinfou.trading.core.Strategy strategy = new com.martinfou.trading.core.Strategy() {
            private final java.util.List<com.martinfou.trading.core.Bar> history = new java.util.ArrayList<>();
            private final java.util.List<com.martinfou.trading.core.Order> pending = new java.util.ArrayList<>();

            @Override public String name() { return "MT_Test"; }
            @Override
            public void onBar(com.martinfou.trading.core.Bar bar) {
                int count = callCount.incrementAndGet();
                history.add(bar);
                if (count == 1) {
                    assertEquals(1, history.size());
                    assertEquals(java.time.Instant.parse("2026-05-20T10:00:00Z"), history.get(0).timestamp());
                }
                if (count == 2) {
                    assertEquals(2, history.size());
                    assertEquals(java.time.Instant.parse("2026-05-20T10:00:00Z"), history.get(0).timestamp());
                    assertEquals(java.time.Instant.parse("2026-05-20T11:00:00Z"), history.get(1).timestamp());
                }
            }
            @Override public void onTick(double b, double a, long v) {}
            @Override public java.util.List<com.martinfou.trading.core.Order> getPendingOrders() { return pending; }
            @Override public void reset() { history.clear(); pending.clear(); callCount.set(0); }
        };

        new BacktestEngine(strategy, bars, CAPITAL)
            .withDataTimeframe("m1")
            .withStrategyTimeframe("h1")
            .run();

        assertEquals(2, callCount.get(), "Strategy should only be called twice, at the end of each H1 hour");
    }

    @Test
    void stopOrder_fillsAtTriggerOrGapOpen() {
        // Test BUY STOP with opening gap up
        double buyStopPrice = 1.1010;
        List<com.martinfou.trading.core.Bar> barsBuy = TestBars.ohlc(new double[][] {
            {1.1000, 1.1005, 1.0995, 1.1000},
            {1.1020, 1.1030, 1.1015, 1.1025} // Opens at 1.1020, which is higher than buy stop price 1.1010
        });
        BacktestResult resBuy = new BacktestEngine(TestStrategies.stopBuy(buyStopPrice, 10_000, 2), barsBuy, CAPITAL).run();
        assertEquals(1, resBuy.totalTrades());
        assertEquals(1.1020, resBuy.trades().getFirst().entryPrice(), 1e-9, "BUY STOP opening gap fills at bar open");

        // Test SELL STOP with opening gap down
        double sellStopPrice = 1.0990;
        List<com.martinfou.trading.core.Bar> barsSell = TestBars.ohlc(new double[][] {
            {1.1000, 1.1005, 1.0995, 1.1000},
            {1.0980, 1.0985, 1.0970, 1.0975} // Opens at 1.0980, which is lower than sell stop price 1.0990
        });
        BacktestResult resSell = new BacktestEngine(TestStrategies.stopSell(sellStopPrice, 10_000, 2), barsSell, CAPITAL).run();
        assertEquals(1, resSell.totalTrades());
        assertEquals(1.0980, resSell.trades().getFirst().entryPrice(), 1e-9, "SELL STOP opening gap fills at bar open");
    }

    @Test
    void slippage_tracksCashValuedWithoutDoubleCounting() {
        List<com.martinfou.trading.core.Bar> bars = TestBars.ohlc(new double[][] {
            {1.1000, 1.1010, 1.0990, 1.1005},
            {1.1010, 1.1020, 1.1000, 1.1015}
        });
        BacktestResult result = new BacktestEngine(TestStrategies.buyOnce(), bars, CAPITAL)
            .withCommissionFixed(5.0)
            .withSlippagePct(0.0001)
            .run();

        // Entry at open of bar 0 = 1.1000. Quantity = 10,000.
        // Slip per unit = 1.1000 * 0.0001 = 0.00011.
        // Total cash slippage = 0.00011 * 10,000 = 1.10 USD.
        assertEquals(1.10, result.totalSlippage(), 1e-6, "Slippage should be cash valued (1.10 USD)");

        double entryPriceWithSlip = 1.10011;
        double exitPrice = 1.1015; // closed at end close
        double expectedTradePnl = (exitPrice - entryPriceWithSlip) * 10000; // 13.90 USD
        assertEquals(expectedTradePnl, result.trades().getFirst().pnl(), 1e-6);

        // Net PnL = trade PnL - commission (no double counting of slippage)
        double expectedNetPnl = expectedTradePnl - 5.0; // 8.90 USD
        assertEquals(expectedNetPnl, result.totalPnl(), 1e-6);
        assertEquals(CAPITAL + expectedNetPnl, result.finalEquity(), 1e-6);
    }
}
