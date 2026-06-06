package com.martinfou.trading.backtest;

import com.martinfou.trading.backtest.harness.HarnessTestBars;
import com.martinfou.trading.core.ForexMarketCalendar;
import com.martinfou.trading.core.LotSizing;
import com.martinfou.trading.core.Trade;
import com.martinfou.trading.strategies.harness.HarnessStrategyCatalog;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spot FX is closed on weekends; the engine must not open or close positions on
 * Saturday/Sunday bars even when they appear in the input series.
 */
class ForexWeekendBacktestTest {

    private static final String SYMBOL = "EUR_USD";

    @Test
    void weekendProbe_monToSun_tradesWeekdaysOnly() {
        List<com.martinfou.trading.core.Bar> bars =
            HarnessTestBars.weekMonToSun(SYMBOL, LocalDate.of(2024, 1, 8));
        BacktestResult result = new BacktestEngine(
            HarnessStrategyCatalog.create("Harness_WeekendProbe", SYMBOL),
            bars,
            LotSizing.DEFAULT_STARTING_CAPITAL
        ).run();

        assertEquals(5, result.totalTrades(), "Mon–Fri only; Sat/Sun bars ignored");
        for (Trade trade : result.trades()) {
            assertFalse(ForexMarketCalendar.isWeekendUtc(trade.entryTime()),
                "entry must not be on weekend: " + trade.entryTime());
            assertFalse(ForexMarketCalendar.isWeekendUtc(trade.exitTime()),
                "exit must not be on weekend: " + trade.exitTime());
        }
    }

    @Test
    void weekendProbe_satSunOnlyBars_produceNoTrades() {
        List<com.martinfou.trading.core.Bar> bars = List.of(
            barAt(LocalDate.of(2024, 1, 13)),
            barAt(LocalDate.of(2024, 1, 14))
        );
        BacktestResult result = new BacktestEngine(
            HarnessStrategyCatalog.create("Harness_WeekendProbe", SYMBOL),
            bars,
            LotSizing.DEFAULT_STARTING_CAPITAL
        ).run();

        assertEquals(0, result.totalTrades());
        assertTrue(result.trades().isEmpty());
    }

    @Test
    void weekendOnlyTrade_neverFillsWhenEngineSkipsWeekends() {
        List<com.martinfou.trading.core.Bar> bars =
            HarnessTestBars.weekMonToSun(SYMBOL, LocalDate.of(2024, 1, 8));
        BacktestResult result = new BacktestEngine(
            HarnessStrategyCatalog.create("Harness_WeekendOnlyTrade", SYMBOL),
            bars,
            LotSizing.DEFAULT_STARTING_CAPITAL
        ).run();

        assertEquals(0, result.totalTrades(),
            "strategy only orders on Sat/Sun; engine must not deliver those bars to onBar");
    }

    private static com.martinfou.trading.core.Bar barAt(LocalDate day) {
        return new com.martinfou.trading.core.Bar(
            SYMBOL,
            day.atTime(12, 0).toInstant(ZoneOffset.UTC),
            1.10, 1.1005, 1.0995, 1.10, 1_000);
    }
}
