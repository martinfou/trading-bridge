package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestEngine;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.strategies.StrategyCatalog;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RunBacktestResolverTest {

    private static final double CAPITAL = 100_000.0;

    @Test
    void backtestFromCatalog_matchesDirectBacktestEngine() {
        List<Bar> bars = sampleBars("EUR_USD", 500);
        BacktestResult direct = new BacktestEngine(
            StrategyCatalog.create("LondonOpenRangeBreakout", "EUR_USD"),
            bars,
            CAPITAL
        ).run();

        BacktestResult viaContext = RunContexts.backtest("LondonOpenRangeBreakout", "EUR_USD", bars, CAPITAL).run();

        assertEquals(direct.totalTrades(), viaContext.totalTrades());
        assertEquals(direct.totalPnl(), viaContext.totalPnl(), 0.01);
        assertEquals(direct.totalReturnPct(), viaContext.totalReturnPct(), 0.0001);
    }

    @Test
    void unknownStrategyId_throws() {
        assertThrows(IllegalArgumentException.class,
            () -> RunContexts.backtest("NoSuchStrategy", "EUR_USD", sampleBars("EUR_USD", 10), CAPITAL));
    }

    private static List<Bar> sampleBars(String symbol, int count) {
        var bars = new ArrayList<Bar>(count);
        var time = Instant.parse("2012-01-01T00:00:00Z");
        double price = 1.30;
        for (int i = 0; i < count; i++) {
            bars.add(new Bar(symbol, time, price, price + 0.001, price - 0.001, price, 1000));
            time = time.plusSeconds(3600);
        }
        return bars;
    }
}
