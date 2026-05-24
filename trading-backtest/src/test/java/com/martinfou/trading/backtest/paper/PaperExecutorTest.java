package com.martinfou.trading.backtest.paper;

import com.martinfou.trading.backtest.BacktestEngine;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.RunContext;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.backtest.TestStrategies;
import com.martinfou.trading.backtest.events.RunEvent;
import com.martinfou.trading.backtest.events.RunEventType;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Strategy;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperExecutorTest {

    private static final double CAPITAL = 100_000.0;

    @Test
    void paperRun_producesTrades_andMatchesBacktestEngine() {
        List<Bar> bars = trendingBars("EUR_USD", 500);
        Strategy strategy = TestStrategies.smaCrossover(5, 20);

        BacktestResult backtest = new BacktestEngine(strategy, bars, CAPITAL).run();
        BacktestResult paper = PaperExecutor.run(strategy, bars, CAPITAL);

        assertTrue(paper.totalTrades() >= 1, "expected at least one trade");
        assertEquals(backtest.totalTrades(), paper.totalTrades());
        assertEquals(backtest.totalPnl(), paper.totalPnl(), 0.01);
        assertEquals(backtest.finalEquity(), paper.finalEquity(), 0.01);
    }

    @Test
    void runContext_paper_emitsEventsWithPaperMode() {
        List<Bar> bars = trendingBars("EUR_USD", 300);
        List<RunEvent> events = new ArrayList<>();

        RunContext.forStrategy(
            "TestPaper",
            TestStrategies.smaCrossover(5, 20),
            "EUR_USD",
            RunMode.PAPER,
            bars,
            CAPITAL,
            events::add).run();

        assertEquals(2, events.size());
        assertEquals(RunEventType.RUN_STARTED, events.get(0).type());
        assertEquals(RunEventType.RUN_ENDED, events.get(1).type());
        assertEquals("PAPER", events.get(0).mode());
        assertEquals("PAPER", events.get(1).mode());
        assertTrue(events.get(1).payload().containsKey("totalTrades"));
    }

    /** Bars with enough movement for SMA crossover to trade. */
    private static List<Bar> trendingBars(String symbol, int count) {
        var bars = new ArrayList<Bar>(count);
        var time = Instant.parse("2012-01-01T08:00:00Z");
        double price = 1.30;
        var rand = new Random(42);
        for (int i = 0; i < count; i++) {
            double open = price;
            double close = open + rand.nextGaussian() * 0.003;
            double high = Math.max(open, close) + 0.002;
            double low = Math.min(open, close) - 0.002;
            price = close;
            bars.add(new Bar(symbol, time, open, high, low, close, 1000));
            time = time.plusSeconds(3600);
        }
        return bars;
    }
}
