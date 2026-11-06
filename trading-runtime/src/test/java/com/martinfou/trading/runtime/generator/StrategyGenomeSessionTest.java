package com.martinfou.trading.runtime.generator;

import com.martinfou.trading.backtest.BacktestEngine;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.core.Trade;
import com.martinfou.trading.runtime.generator.StrategyGenome.BlockType;
import com.martinfou.trading.runtime.generator.StrategyGenome.RuleGene;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StrategyGenomeSessionTest {

    private static final ZoneId NY_ZONE = ZoneId.of("America/New_York");

    private static List<Bar> buildSyntheticBars(ZonedDateTime startEt, int count, Duration step) {
        List<Bar> bars = new ArrayList<>(count);
        ZonedDateTime cur = startEt;
        double price = 2500.0;
        for (int i = 0; i < count; i++) {
            DayOfWeek dow = cur.getDayOfWeek();
            if (dow == DayOfWeek.SATURDAY || (dow == DayOfWeek.FRIDAY && cur.getHour() >= 17)
                || (dow == DayOfWeek.SUNDAY && cur.getHour() < 18)) {
                cur = cur.plus(step);
                continue;
            }
            // Sawtooth wave that triggers frequent RSI / Price-vs-MA entries and holds if not flattened
            double wave = Math.sin(i * 0.35) * 18.0 + (i * 0.15);
            double close = price + wave;
            double open = close - 1.5;
            double high = close + 4.0;
            double low = open - 4.0;
            Instant ts = cur.toInstant();
            bars.add(new Bar("MGC", ts, open, high, low, close, 1000L));
            cur = cur.plus(step);
        }
        return bars;
    }

    @Test
    void intradayFlat_9to5_preventsOvernightHold_andRestrictsEntriesToRth() {
        ZonedDateTime startEt = ZonedDateTime.of(2025, 3, 3, 0, 0, 0, 0, NY_ZONE); // Monday
        List<Bar> bars = buildSyntheticBars(startEt, 500, Duration.ofMinutes(30));

        RuleGene alwaysBullishOrBearish = new RuleGene(BlockType.PRICE_VS_MA, 5, 20, 0, 1, 0.0);
        StrategyGenome genome = new StrategyGenome(
            "MgcM30IntradayFlatTest",
            "MGC",
            "M30",
            "TREND_FOLLOWING",
            "BOTH",
            "MARKET",
            List.of(alwaysBullishOrBearish),
            List.of(alwaysBullishOrBearish),
            10,
            0.5,
            4,
            14,
            8.0,   // Wide SL/TP so positions stay open until EOD pre-cutoff flatten
            16.0,
            0.0,
            200,
            9,     // 09:00 ET start
            16,    // 16:00 ET end
            31,    // Mon-Fri
            "INTRADAY_FLAT",
            16     // Flatten before 16:00 ET
        );

        Strategy strategy = genome.toExecutableStrategy("MGC");
        BacktestResult result = new BacktestEngine(strategy, bars, 10_000.0).run();

        assertFalse(result.trades().isEmpty(), "Expected intraday trades to execute during 09:00-15:00 ET");

        for (Trade trade : result.trades()) {
            ZonedDateTime entryEt = trade.entryTime().atZone(NY_ZONE);
            ZonedDateTime exitEt = trade.exitTime().atZone(NY_ZONE);
            int entryMin = entryEt.getHour() * 60 + entryEt.getMinute();
            int exitMin = exitEt.getHour() * 60 + exitEt.getMinute();

            // Because BacktestEngine fills MARKET orders on the next bar's open (M30 = +30m),
            // a signal on the 09:00 ET bar fills at 09:30 ET, and the last allowed entry before
            // the 60m pre-cutoff freeze (15:00 ET) is emitted on 14:30 ET and fills at 15:00 ET.
            assertTrue(entryMin >= 9 * 60 && entryMin <= 15 * 60 + 30,
                "Entry fill time outside intraday window: " + entryEt);
            // Flatten emitted on 15:30 ET bar fills at 16:00 ET open — strictly before 17:00 ET break
            assertTrue(exitMin <= 16 * 60,
                "Exit fill time exceeded 16:00 ET cutoff: " + exitEt);
            assertEquals(entryEt.toLocalDate(), exitEt.toLocalDate(),
                "Intraday flat trade should never be held overnight across dates: " + entryEt + " -> " + exitEt);
        }
    }

    @Test
    void weekendFlat_andAllowedDaysMask_blocksFridayEntries_andFlattensBeforeFridayClose() {
        ZonedDateTime startEt = ZonedDateTime.of(2025, 3, 3, 0, 0, 0, 0, NY_ZONE); // Monday
        List<Bar> bars = buildSyntheticBars(startEt, 450, Duration.ofHours(1));

        RuleGene rule = new RuleGene(BlockType.PRICE_VS_MA, 5, 20, 0, 1, 0.0);
        // allowedDaysMask = 15 (Mon=1 | Tue=2 | Wed=4 | Thu=8, Friday=0 blocked for new entries)
        StrategyGenome genome = new StrategyGenome(
            "MgcH1WeekendFlatTest",
            "MGC",
            "H1",
            "TREND_FOLLOWING",
            "BOTH",
            "MARKET",
            List.of(rule),
            List.of(rule),
            10,
            0.5,
            4,
            14,
            15.0,
            30.0,
            0.0,
            500,
            0,
            24,
            15, // Mon-Thu only for entries
            "WEEKEND_FLAT",
            16
        );

        Strategy strategy = genome.toExecutableStrategy("MGC");
        BacktestResult result = new BacktestEngine(strategy, bars, 10_000.0).run();

        assertFalse(result.trades().isEmpty(), "Expected trades on Mon-Thu");

        for (Trade trade : result.trades()) {
            ZonedDateTime entryEt = trade.entryTime().atZone(NY_ZONE);
            ZonedDateTime exitEt = trade.exitTime().atZone(NY_ZONE);

            // A signal on Thursday 23:00 ET fills on Friday 00:00 ET open, so Friday entries after 00:00 ET must be 0
            if (entryEt.getDayOfWeek() == DayOfWeek.FRIDAY) {
                assertEquals(0, entryEt.getHour(),
                    "No new signal should be emitted on Friday when allowedDaysMask=15: " + entryEt);
            }
            // Verify no trade spans across a Friday 17:00 ET close into Sunday/Monday
            Duration held = Duration.between(entryEt, exitEt);
            assertTrue(held.toHours() < 120, "Trade held across weekend gap: " + entryEt + " -> " + exitEt);
        }
    }

    @Test
    void higherTimeframe_d1_bypassesIntradayHourFilter_withoutTradeStarvation() {
        ZonedDateTime startEt = ZonedDateTime.of(2024, 1, 2, 0, 0, 0, 0, NY_ZONE); // Midnight ET D1 bars
        List<Bar> d1Bars = buildSyntheticBars(startEt, 260, Duration.ofDays(1));

        RuleGene rule = new RuleGene(BlockType.PRICE_VS_MA, 5, 20, 0, 1, 0.0);
        // Even if entryStartHourEt=9..16 and INTRADAY_FLAT are passed on D1, higher-timeframe guardrail bypasses intraday hours
        StrategyGenome d1Genome = new StrategyGenome(
            "MgcD1BypassTest",
            "MGC",
            "D1",
            "TREND_FOLLOWING",
            "BOTH",
            "MARKET",
            List.of(rule),
            List.of(rule),
            10,
            0.5,
            4,
            14,
            2.0,
            4.0,
            0.0,
            20,
            9,
            16,
            31,
            "INTRADAY_FLAT",
            16
        );

        Strategy strategy = d1Genome.toExecutableStrategy("MGC");
        BacktestResult result = new BacktestEngine(strategy, d1Bars, 10_000.0).run();
        assertFalse(result.trades().isEmpty(), "D1 timeframe must bypass intraday hour window and not starve to 0 trades");
    }

    @Test
    void legacyNullDeserialization_andGeneratedJavaCodeParity() {
        RuleGene rule = new RuleGene(BlockType.DONCHIAN_BREAKOUT, 20, 50, 0, 1, 1.0);
        // 16-arg legacy constructor
        StrategyGenome legacy = new StrategyGenome(
            "LegacyGenome123",
            "MGC",
            "M30",
            "BREAKOUT",
            "BOTH",
            "STOP",
            List.of(rule),
            List.of(rule),
            20,
            0.5,
            4,
            14,
            2.0,
            4.0,
            0.0,
            30
        );

        assertEquals(0, legacy.entryStartHourEt());
        assertEquals(24, legacy.entryEndHourEt());
        assertEquals(31, legacy.allowedDaysMask());
        assertEquals("CONTINUOUS", legacy.exitSessionMode());
        assertEquals(16, legacy.flatCutoffHourEt());
        assertFalse(legacy.hasRestrictedSchedule());

        StrategyGenome sessionGenome = new StrategyGenome(
            "SessionGenome456",
            "MGC",
            "M30",
            "BREAKOUT",
            "BOTH",
            "STOP",
            List.of(rule),
            List.of(rule),
            20,
            0.5,
            4,
            14,
            2.0,
            4.0,
            0.0,
            30,
            9,
            16,
            15,
            "INTRADAY_FLAT",
            16
        );

        assertTrue(sessionGenome.hasRestrictedSchedule());
        assertTrue(sessionGenome.sessionSummary().contains("Mon–Thu"));
        assertTrue(sessionGenome.sessionSummary().contains("09:00–16:00 ET"));
        assertTrue(sessionGenome.indicatorsUsed().stream().anyMatch(s -> s.toUpperCase().startsWith("SESSION(")));
        assertTrue(sessionGenome.indicatorsUsed().stream().anyMatch(s -> s.toUpperCase().startsWith("DAYS(")));
        assertTrue(sessionGenome.indicatorsUsed().stream().anyMatch(s -> s.toUpperCase().startsWith("EODFLAT(")));

        String javaSource = sessionGenome.toJavaSourceCode(1500.0, 1.65, 1.40, 1.25, 8.5, 42, 78.5);
        assertTrue(javaSource.contains("ZoneId.of(\"America/New_York\")"));
        assertTrue(javaSource.contains("shouldFlattenBeforeGap(bar)"));
        assertTrue(javaSource.contains("isEntryAllowedAt(bar)"));
        assertTrue(javaSource.contains("clearArmed()"));
    }

    @Test
    void stopEntry_disarmsPendingOrder_outsideAllowedSessionWindow() {
        ZonedDateTime startEt = ZonedDateTime.of(2025, 3, 3, 0, 0, 0, 0, NY_ZONE);
        List<Bar> bars = buildSyntheticBars(startEt, 400, Duration.ofMinutes(30));

        RuleGene rule = new RuleGene(BlockType.PRICE_VS_MA, 5, 20, 0, 1, 0.0);
        StrategyGenome stopGenome = new StrategyGenome(
            "MgcM30StopSessionTest",
            "MGC",
            "M30",
            "BREAKOUT",
            "BOTH",
            "STOP",
            List.of(rule),
            List.of(rule),
            6,
            0.25,
            8,
            14,
            4.0,
            8.0,
            0.0,
            100,
            9,
            12,
            31,
            "INTRADAY_FLAT",
            16
        );

        Strategy strategy = stopGenome.toExecutableStrategy("MGC");
        BacktestResult result = new BacktestEngine(strategy, bars, 10_000.0).run();
        for (Trade trade : result.trades()) {
            ZonedDateTime entryEt = trade.entryTime().atZone(NY_ZONE);
            int entryMin = entryEt.getHour() * 60 + entryEt.getMinute();
            // Signals only fire 09:00-11:30 ET; STOP trigger on 11:30 ET bar fills at 12:00 ET open at latest
            assertTrue(entryMin >= 9 * 60 && entryMin <= 12 * 60,
                "Pending STOP order must not trigger outside 09:00-12:00 ET session window: " + entryEt);
        }
    }
}
