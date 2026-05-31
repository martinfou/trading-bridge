package com.martinfou.trading.backtest;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.core.Trade;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parameterized platform tests: each scenario runs through BACKTEST and PAPER
 * via {@link RunContext} and asserts parity plus accounting invariants.
 *
 * <p>Note: {@link RunMode#PAPER} in {@link RunContext} currently delegates to the same
 * {@link BacktestEngine} path as BACKTEST (paper stub). Parity assertions guard against
 * accidental divergence when Epic 4 introduces a live paper executor.
 */
class PlatformRobustnessTest {

    private static final double CAPITAL = 10_000.0;
    private static final String SYMBOL = TestBars.DEFAULT_SYMBOL;

    record PlatformCase(
        String name,
        Strategy strategy,
        List<Bar> bars,
        int expectedTrades,
        Consumer<BacktestResult> verifier
    ) {
        PlatformCase(String name, Strategy strategy, List<Bar> bars, int expectedTrades) {
            this(name, strategy, bars, expectedTrades, r -> {});
        }

        @Override
        public String toString() {
            return name;
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("normalBehaviorCases")
    void normalBehavior_backtestAndPaperMatch(PlatformCase scenario) {
        runPlatformScenario(scenario);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("edgeCaseScenarios")
    void edgeCases_platformRobust(PlatformCase scenario) {
        runPlatformScenario(scenario);
    }

    @Test
    void liveMode_throwsUnsupported() {
        List<Bar> bars = TestBars.flat(1, 1.1000);
        var ctx = RunContext.forStrategy(TestStrategies.noOp(), SYMBOL, RunMode.LIVE, bars, CAPITAL);
        assertThrows(UnsupportedOperationException.class, ctx::run);
    }

    static Stream<PlatformCase> normalBehaviorCases() {
        return Stream.of(
            new PlatformCase("noOp_idle", TestStrategies.noOp(), TestBars.flat(5, 1.1000), 0),
            new PlatformCase("buyOnce_roundTripAtEnd", TestStrategies.buyOnce(),
                TestBars.ohlc(new double[][] {
                    {1.2500, 1.2510, 1.2490, 1.2505},
                    {1.2510, 1.2520, 1.2500, 1.2515}
                }), 1,
                r -> assertEquals(1.2500, r.trades().getFirst().entryPrice(), 1e-9)),
            new PlatformCase("buyThenSell_explicitExit", TestStrategies.buyThenSell(),
                TestBars.ohlc(new double[][] {
                    {1.1000, 1.1010, 1.0990, 1.1005},
                    {1.1010, 1.1020, 1.1000, 1.1015},
                    {1.1020, 1.1030, 1.1010, 1.1025}
                }), 1,
                r -> assertEquals(1.1010, r.trades().getFirst().exitPrice(), 1e-9)),
            new PlatformCase("alternating_twoRoundTrips", TestStrategies.alternatingRoundTrips(2),
                TestBars.flat(5, 1.1000), 2),
            new PlatformCase("limitBuy_fillsAtLimit", TestStrategies.limitBuy(1.1000, 10_000),
                TestBars.ohlc(new double[][] {
                    {1.1010, 1.1020, 1.0990, 1.1015},
                    {1.1015, 1.1025, 1.1010, 1.1020}
                }), 1,
                r -> assertEquals(1.1000, r.trades().getFirst().entryPrice(), 1e-9)),
            new PlatformCase("stopBuy_fillsOnBreakout", TestStrategies.stopBuy(1.1010, 10_000, 2),
                TestBars.ohlc(new double[][] {
                    {1.1000, 1.1005, 1.0990, 1.1002},
                    {1.1005, 1.1020, 1.1000, 1.1015}
                }), 1,
                r -> assertEquals(1.1010, r.trades().getFirst().entryPrice(), 1e-9)),
            new PlatformCase("takeProfit_exitsAtTp", TestStrategies.buyWithTakeProfit(1.1050),
                TestBars.ohlc(new double[][] {
                    {1.1000, 1.1010, 1.0990, 1.1005},
                    {1.1040, 1.1060, 1.1030, 1.1055}
                }), 1,
                r -> assertEquals(1.1050, r.trades().getFirst().exitPrice(), 1e-9)),
            new PlatformCase("smaCrossover_producesTrades", TestStrategies.smaCrossover(3, 10),
                TestBars.uptrend(30, 1.1000, 0.0010), -1,
                r -> assertTrue(r.totalTrades() > 0, "uptrend should produce SMA crossover trades"))
        );
    }

    static Stream<PlatformCase> edgeCaseScenarios() {
        return Stream.of(
            new PlatformCase("emptyBars_noTrades", TestStrategies.noOp(), List.of(), 0),
            new PlatformCase("singleBar_buyClosedAtClose", TestStrategies.buyOnce(),
                TestBars.ohlc(new double[][] {{1.2000, 1.2010, 1.1990, 1.2005}}), 1),
            new PlatformCase("limitBuy_neverFills", TestStrategies.limitBuy(1.0500, 10_000, 3),
                TestBars.flat(3, 1.1000), 0),
            new PlatformCase("stopLoss_triggersAtSl", TestStrategies.buyWithStopLoss(1.1950),
                TestBars.ohlc(new double[][] {
                    {1.2000, 1.2010, 1.1990, 1.2000},
                    {1.1990, 1.1995, 1.1940, 1.1945}
                }), 1,
                r -> assertEquals(1.1950, r.trades().getFirst().exitPrice(), 1e-9)),
            new PlatformCase("sameSide_addsQuantity", TestStrategies.buyTwiceSameSide(),
                TestBars.flat(4, 1.1000), 1,
                r -> assertEquals(10_000, r.trades().getFirst().quantity(), 1e-9)),
            new PlatformCase("delayedEntry_bar2Only", TestStrategies.delayedMarketBuy(2, 10_000),
                TestBars.flat(4, 1.1000), 1,
                r -> assertEquals(1.1000, r.trades().getFirst().entryPrice(), 1e-9)),
            new PlatformCase("noOrdersUntilBar3", TestStrategies.delayedMarketBuy(3, 5_000),
                TestBars.flat(5, 1.1000), 1),
            new PlatformCase("sellOnce_singleBar", TestStrategies.sellOnce(),
                TestBars.ohlc(new double[][] {{1.1000, 1.1010, 1.0990, 1.1005}}), 1),
            new PlatformCase("sellThenBuy_coverWithoutReversal", TestStrategies.sellThenBuy(),
                TestBars.ohlc(new double[][] {
                    {1.1000, 1.1010, 1.0990, 1.1005},
                    {1.1010, 1.1020, 1.1000, 1.1015},
                    {1.1020, 1.1030, 1.1010, 1.1025}
                }), 1,
                r -> assertEquals(1.1010, r.trades().getFirst().exitPrice(), 1e-9)),
            new PlatformCase("stopLoss_gapsThrough_exitsAtSl", TestStrategies.buyWithStopLoss(1.1950),
                TestBars.ohlc(new double[][] {
                    {1.2000, 1.2010, 1.1990, 1.2000},
                    {1.1880, 1.1900, 1.1850, 1.1870}
                }), 1,
                r -> {
                    assertEquals(1.1950, r.trades().getFirst().exitPrice(), 1e-9);
                    assertTrue(r.trades().getFirst().exitPrice() > 1.1880, "SL price, not gap open");
                }),
            new PlatformCase("slAndTp_sameBar_slWins", TestStrategies.buyWithStopLossAndTakeProfit(1.1950, 1.2100),
                TestBars.ohlc(new double[][] {
                    {1.2000, 1.2010, 1.1990, 1.2000},
                    {1.2050, 1.2150, 1.1940, 1.2120}
                }), 1,
                r -> assertEquals(1.1950, r.trades().getFirst().exitPrice(), 1e-9)),
            new PlatformCase("limitSell_fillsAtLimit", TestStrategies.limitSell(1.1050, 10_000),
                TestBars.ohlc(new double[][] {
                    {1.1000, 1.1060, 1.0990, 1.1040},
                    {1.1040, 1.1050, 1.1030, 1.1045}
                }), 1,
                r -> assertEquals(1.1050, r.trades().getFirst().entryPrice(), 1e-9)),
            new PlatformCase("limitSell_neverFills", TestStrategies.limitSell(1.1500, 10_000, 3),
                TestBars.flat(3, 1.1000), 0),
            new PlatformCase("stopSell_fillsOnBreakdown", TestStrategies.stopSell(1.0990, 10_000, 2),
                TestBars.ohlc(new double[][] {
                    {1.1010, 1.1020, 1.1000, 1.1015},
                    {1.1000, 1.1005, 1.0980, 1.0995}
                }), 1,
                r -> assertEquals(1.0995, r.trades().getFirst().entryPrice(), 1e-9,
                    "STOP SELL fill uses max(bar.close(), stop) per BacktestEngine")),
            new PlatformCase("doubleMarketBuySameBar_addsQty", TestStrategies.doubleMarketBuySameBar(),
                TestBars.flat(2, 1.1000), 1,
                r -> assertEquals(10_000, r.trades().getFirst().quantity(), 1e-9)),
            new PlatformCase("short_stopLoss_triggers", TestStrategies.sellWithStopLoss(1.1050),
                TestBars.ohlc(new double[][] {
                    {1.1000, 1.1010, 1.0990, 1.1005},
                    {1.1040, 1.1060, 1.1030, 1.1055}
                }), 1,
                r -> assertEquals(1.1050, r.trades().getFirst().exitPrice(), 1e-9))
        );
    }

    private static void runPlatformScenario(PlatformCase scenario) {
        BacktestResult backtest = runMode(scenario.strategy(), scenario.bars(), RunMode.BACKTEST);
        BacktestResult paper = runMode(scenario.strategy(), scenario.bars(), RunMode.PAPER);

        assertPlatformParity(backtest, paper);
        if (scenario.expectedTrades() >= 0) {
            assertEquals(scenario.expectedTrades(), backtest.totalTrades(), "trade count");
        }
        assertAccountingInvariants(backtest);
        scenario.verifier().accept(backtest);
    }

    private static BacktestResult runMode(Strategy strategy, List<Bar> bars, RunMode mode) {
        return RunContext.forStrategy(strategy, SYMBOL, mode, bars, CAPITAL).run();
    }

    private static void assertPlatformParity(BacktestResult backtest, BacktestResult paper) {
        assertEquals(backtest.totalTrades(), paper.totalTrades(), "BACKTEST vs PAPER trades");
        assertEquals(backtest.totalPnl(), paper.totalPnl(), 1e-6, "BACKTEST vs PAPER PnL");
        assertEquals(backtest.totalReturnPct(), paper.totalReturnPct(), 1e-6, "BACKTEST vs PAPER return");
        assertEquals(backtest.finalEquity(), paper.finalEquity(), 1e-6, "BACKTEST vs PAPER equity");
    }

    private static void assertAccountingInvariants(BacktestResult result) {
        double tradePnlSum = result.trades().stream().mapToDouble(Trade::pnl).sum();
        double expectedTotalPnl = tradePnlSum - result.totalCommission() - result.totalSlippage();
        assertEquals(expectedTotalPnl, result.totalPnl(), 1e-6, "totalPnl = sum(trade pnl) - costs");
        assertEquals(CAPITAL + result.totalPnl(), result.finalEquity(), 1e-6,
            "finalEquity = initialCapital + totalPnl");
        assertEquals(result.totalPnl() / CAPITAL * 100.0, result.totalReturnPct(), 1e-6,
            "totalReturnPct = totalPnl / initialCapital * 100");
    }
}
