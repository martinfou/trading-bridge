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
    void oppositeSideMarket_closesWithoutReversal() {
        List<com.martinfou.trading.core.Bar> bars = TestBars.ohlc(new double[][] {
            {1.1000, 1.1010, 1.0990, 1.1005},
            {1.1010, 1.1020, 1.1000, 1.1015},
            {1.1020, 1.1030, 1.1010, 1.1025}
        });

        BacktestResult result = new BacktestEngine(TestStrategies.buyThenSell(), bars, CAPITAL).run();

        assertEquals(1, result.totalTrades(), "one round-trip, not two entries");
        assertEquals(bars.get(1).open(), result.trades().getFirst().exitPrice(), 1e-9,
            "opposite-side MARKET closes at bar open, no reversal");
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
}
