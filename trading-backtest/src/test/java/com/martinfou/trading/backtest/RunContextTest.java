package com.martinfou.trading.backtest;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Strategy;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RunContextTest {

    private static final double CAPITAL = 100_000.0;

    @Test
    void emptyBars_producesZeroTrades() {
        var ctx = RunContext.forStrategy(TestStrategies.noOp(), "EUR_USD", RunMode.BACKTEST, List.of(), CAPITAL);
        BacktestResult result = ctx.run();
        assertEquals(0, result.totalTrades());
    }

    @Test
    void forStrategy_matchesDirectBacktestEngine() {
        List<Bar> bars = sampleBars("EUR_USD", 500);
        Strategy strategy = TestStrategies.smaCrossover(5, 20);

        BacktestResult direct = new BacktestEngine(strategy, bars, CAPITAL).run();
        BacktestResult viaContext = RunContext.forStrategy(strategy, "EUR_USD", RunMode.BACKTEST, bars, CAPITAL).run();

        assertEquals(direct.totalTrades(), viaContext.totalTrades());
        assertEquals(direct.totalPnl(), viaContext.totalPnl(), 0.01);
        assertEquals(direct.totalReturnPct(), viaContext.totalReturnPct(), 0.0001);
    }

    @Test
    void forStrategy_exposesMetadata() {
        List<Bar> bars = sampleBars("GBP_JPY", 10);
        var ctx = RunContext.forStrategy(
            "TestStrategy",
            TestStrategies.noOp("TestStrategy"),
            "GBP_JPY",
            RunMode.BACKTEST,
            bars,
            CAPITAL,
            null);

        assertEquals(RunMode.BACKTEST, ctx.mode());
        assertEquals("GBP_JPY", ctx.symbol());
        assertEquals("TestStrategy", ctx.strategyId());
        assertEquals(CAPITAL, ctx.initialCapital());
        assertEquals(10, ctx.bars().size());
    }

    private static List<Bar> sampleBars(String symbol, int count) {
        var bars = new ArrayList<Bar>(count);
        var time = Instant.parse("2012-01-01T00:00:00Z");
        double price = symbol.contains("JPY") ? 120.0 : 1.30;
        for (int i = 0; i < count; i++) {
            bars.add(new Bar(symbol, time, price, price + 0.001, price - 0.001, price, 1000));
            time = time.plusSeconds(3600);
            price += 0.0001;
        }
        return bars;
    }
}
