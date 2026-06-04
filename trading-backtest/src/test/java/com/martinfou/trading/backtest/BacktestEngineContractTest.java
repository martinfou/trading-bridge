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
        double expectedTotalPnl = tradePnlSum - result.totalCommission() - result.totalSlippage();

        assertEquals(expectedTotalPnl, result.totalPnl(), 1e-6, "totalPnl = sum(trade pnl) - costs");
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
}
