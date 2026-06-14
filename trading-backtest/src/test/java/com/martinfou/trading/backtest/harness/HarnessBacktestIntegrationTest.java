package com.martinfou.trading.backtest.harness;

import com.martinfou.trading.backtest.BacktestEngine;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.LotSizing;
import com.martinfou.trading.strategies.StrategyCatalog;
import com.martinfou.trading.strategies.StrategyCatalog.Family;
import com.martinfou.trading.strategies.harness.DailyRoundTripStrategy;
import com.martinfou.trading.strategies.harness.HarnessStrategyCatalog;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HarnessBacktestIntegrationTest {

    private static final String SYMBOL = "EUR_USD";
    @Test
    void neverTrade_zeroTradesAndFlatEquity() {
        List<Bar> bars = HarnessTestBars.repeat(SYMBOL, 100, 1.10);
        BacktestResult result = run(HarnessStrategyCatalog.create("Harness_NeverTrade", SYMBOL), bars);
        assertEquals(0, result.totalTrades());
        assertEquals(LotSizing.DEFAULT_STARTING_CAPITAL, result.finalEquity(), 0.01);
    }

    @Test
    void buyOnceHold_oneTrade() {
        List<Bar> bars = HarnessTestBars.repeat(SYMBOL, 20, 1.10);
        BacktestResult result = run(HarnessStrategyCatalog.create("Harness_BuyOnceHold", SYMBOL), bars);
        assertEquals(1, result.totalTrades());
    }

    @Test
    void buyThenCloseNextBar_repeatedTrades() {
        List<Bar> bars = HarnessTestBars.repeat(SYMBOL, 5, 1.10);
        BacktestResult result = run(HarnessStrategyCatalog.create("Harness_BuyThenCloseNextBar", SYMBOL), bars);
        assertEquals(3, result.totalTrades());
    }

    @Test
    void limitNeverFills_zeroTrades() {
        List<Bar> bars = HarnessTestBars.repeat(SYMBOL, 50, 1.10);
        BacktestResult result = run(HarnessStrategyCatalog.create("Harness_LimitNeverFills", SYMBOL), bars);
        assertEquals(0, result.totalTrades());
    }

    @Test
    void weekendProbe_monToSun_fiveTrades() {
        List<Bar> bars = HarnessTestBars.weekMonToSun(SYMBOL, LocalDate.of(2024, 1, 8));
        BacktestResult result = run(HarnessStrategyCatalog.create("Harness_WeekendProbe", SYMBOL), bars);
        assertEquals(5, result.totalTrades());
    }

    @Test
    void openCloseSameBar_oneTradePerBar() {
        List<Bar> bars = HarnessTestBars.repeat(SYMBOL, 5, 1.10);
        BacktestResult result = run(HarnessStrategyCatalog.create("Harness_OpenCloseSameBar", SYMBOL), bars);
        assertEquals(5, result.totalTrades());
    }

    @Test
    void dailyOpenClose_twoTradesOverTwoDays() {
        List<Bar> bars = HarnessTestBars.h1Days(SYMBOL, LocalDate.of(2024, 1, 1), 2);
        BacktestResult result = run(HarnessStrategyCatalog.create("Harness_DailyOpenClose", SYMBOL), bars);
        assertEquals(2, result.totalTrades());
    }

    @Test
    void dailyRoundTrip_twoTradesOverTwoDays() {
        List<Bar> bars = HarnessTestBars.h1Days(SYMBOL, LocalDate.of(2024, 1, 1), 2);
        var strategy = new DailyRoundTripStrategy(SYMBOL);
        BacktestResult result = run(strategy, bars);
        assertEquals(2, result.totalTrades());
    }

    @Test
    void weeklyRoundTrip_oneTradeMonToFri() {
        List<Bar> bars = HarnessTestBars.weekMonToFri(SYMBOL, LocalDate.of(2024, 1, 8));
        BacktestResult result = run(HarnessStrategyCatalog.create("Harness_WeeklyRoundTrip", SYMBOL), bars);
        assertEquals(1, result.totalTrades());
    }

    @Test
    void strategyCatalog_registersHarnessFamily() {
        assertTrue(StrategyCatalog.contains("Harness_DailyRoundTrip"));
        assertEquals(Family.HARNESS, StrategyCatalog.family("Harness_DailyRoundTrip"));
        assertEquals(SYMBOL, StrategyCatalog.defaultSymbol("Harness_DailyRoundTrip"));
        BacktestResult result = run(StrategyCatalog.create("Harness_NeverTrade", SYMBOL), HarnessTestBars.repeat(SYMBOL, 10, 1.10));
        assertEquals(0, result.totalTrades());
    }

    private static BacktestResult run(com.martinfou.trading.core.Strategy strategy, List<Bar> bars) {
        return new BacktestEngine(strategy, bars, LotSizing.DEFAULT_STARTING_CAPITAL).run();
    }
}
