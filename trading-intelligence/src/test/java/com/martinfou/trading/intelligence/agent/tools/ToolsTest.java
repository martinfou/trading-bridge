package com.martinfou.trading.intelligence.agent.tools;

import com.martinfou.trading.core.agent.MarketDirection;
import com.martinfou.trading.data.EconomicCalendar;
import com.martinfou.trading.intelligence.agent.SeasonalityData;
import com.martinfou.trading.intelligence.agent.SentimentData;
import com.martinfou.trading.intelligence.brief.WeeklyIntelBrief;
import com.martinfou.trading.intelligence.brief.WeeklyIntelBriefIO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.parallel.ResourceLock(org.junit.jupiter.api.parallel.Resources.SYSTEM_PROPERTIES)
class ToolsTest {

    @TempDir
    static Path tempDir;

    private static Path tempBriefPath;
    private static Path tempBarsPath;
    private static Instant testCutoff;

    @BeforeAll
    static void setUp() throws IOException {
        // Redirect files to temporary directories to prevent overwriting production data
        Path intelRoot = tempDir.resolve("weekly-intel");
        Path barsDir = tempDir.resolve("historical/bars");
        Files.createDirectories(intelRoot);
        Files.createDirectories(barsDir);

        System.setProperty("trading.intel.dir", intelRoot.toAbsolutePath().toString());
        System.setProperty("trading.data.dir", barsDir.toAbsolutePath().toString());
        System.setProperty("trading.mode", "backtest"); // Ensure OANDA live requests are skipped in test

        LocalDate testDate = LocalDate.of(2026, 6, 2);
        tempBriefPath = WeeklyIntelBriefIO.briefPathForDate(intelRoot, testDate);
        testCutoff = testDate.atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(3600); // 1 hour after brief date

        WeeklyIntelBrief.OandaRetailEntry eurUsdSentiment = new WeeklyIntelBrief.OandaRetailEntry(
            "EUR/USD", 45.0, 55.0, "Short"
        );
        WeeklyIntelBrief.OandaRetailEntry gbpUsdSentiment = new WeeklyIntelBrief.OandaRetailEntry(
            "GBP/USD", 70.0, 30.0, "Long"
        );

        WeeklyIntelBrief.CalendarEventEntry nfpEvent = new WeeklyIntelBrief.CalendarEventEntry(
            "ff-2026-06-05-usd-nfp",
            "Non-Farm Employment Change",
            "USD",
            "HIGH",
            Instant.parse("2026-06-05T12:30:00Z"),
            "forexfactory"
        );

        WeeklyIntelBrief brief = new WeeklyIntelBrief(
            testDate.atStartOfDay(ZoneOffset.UTC).toInstant(),
            LocalDate.of(2026, 6, 8),
            List.of(nfpEvent),
            List.of(),
            List.of(),
            new WeeklyIntelBrief.SentimentBlock(List.of(eurUsdSentiment, gbpUsdSentiment), List.of()),
            List.of(),
            com.martinfou.trading.intelligence.brief.IngestStatus.OK
        );

        WeeklyIntelBriefIO.write(brief, tempBriefPath);

        tempBarsPath = barsDir.resolve("EUR_USD_H1_H1.bars");

        // Write 4 mock bars (size 44 bytes each)
        // Week 21 of 2025 and 2026
        ByteBuffer buf = ByteBuffer.allocate(44 * 4);
        
        // Bar 1: 2025-05-19T09:00:00Z (Monday open 1.1000)
        buf.putLong(Instant.parse("2025-05-19T09:00:00Z").getEpochSecond());
        buf.putDouble(1.1000); // open
        buf.putDouble(1.1050); // high
        buf.putDouble(1.0990); // low
        buf.putDouble(1.1010); // close
        buf.putInt(100);       // volume

        // Bar 2: 2025-05-23T17:00:00Z (Friday close 1.1100) -> return is +100 pips
        buf.putLong(Instant.parse("2025-05-23T17:00:00Z").getEpochSecond());
        buf.putDouble(1.1010); // open
        buf.putDouble(1.1120); // high
        buf.putDouble(1.1000); // low
        buf.putDouble(1.1100); // close
        buf.putInt(100);       // volume

        // Bar 3: 2026-05-18T09:00:00Z (Monday open 1.1200)
        buf.putLong(Instant.parse("2026-05-18T09:00:00Z").getEpochSecond());
        buf.putDouble(1.1200); // open
        buf.putDouble(1.1250); // high
        buf.putDouble(1.1190); // low
        buf.putDouble(1.1210); // close
        buf.putInt(100);       // volume

        // Bar 4: 2026-05-22T17:00:00Z (Friday close 1.1350) -> return is +150 pips
        buf.putLong(Instant.parse("2026-05-22T17:00:00Z").getEpochSecond());
        buf.putDouble(1.1210); // open
        buf.putDouble(1.1360); // high
        buf.putDouble(1.1200); // low
        buf.putDouble(1.1350); // close
        buf.putInt(100);       // volume

        Files.write(tempBarsPath, buf.array());
    }

    @AfterAll
    static void tearDown() {
        System.clearProperty("trading.intel.dir");
        System.clearProperty("trading.data.dir");
        System.clearProperty("trading.mode");
    }

    @Test
    void testMacroToolsFilteringAndMasking() {
        MacroTools macroTools = new MacroTools();

        // 1. Test filtering THIS_WEEK events. FOMC is high impact, Germany PMI is medium impact.
        Instant start = Instant.parse("2026-05-18T00:00:00Z");
        Instant end = Instant.parse("2026-05-24T23:59:59Z");
        
        // FOMC is on 2026-05-20T22:00:00Z (high), UK CPI on 2026-05-20T09:00:00Z (high), Germany PMI on 2026-05-21T11:00:00Z (medium)
        Instant cutoffBeforeFomc = Instant.parse("2026-05-20T12:00:00Z"); // After UK CPI, before FOMC

        List<EconomicCalendar.Event> events = macroTools.fetchEconomicCalendar(start, end, cutoffBeforeFomc);

        assertFalse(events.isEmpty());
        // Verify only HIGH impact events returned
        for (EconomicCalendar.Event e : events) {
            assertEquals("HIGH", e.impact());
        }

        // Verify masking: UK CPI (before cutoff) actual should be kept if it was set (in THIS_WEEK actual is "", previous "+3.9%", forecast "+3.4%")
        // FOMC (after cutoff) actual MUST be empty string
        EconomicCalendar.Event fomc = events.stream()
            .filter(e -> e.event().contains("FOMC"))
            .findFirst()
            .orElse(null);

        assertNotNull(fomc);
        assertEquals("", fomc.actual());
    }

    @Test
    void testSentimentToolsHistoricalBriefLookup() {
        SentimentTools sentimentTools = new SentimentTools();

        // Query EUR_USD at testCutoff (which is 1 hour after the mock brief generation)
        SentimentData eurUsdSentiment = sentimentTools.fetchMarketSentiment("EUR_USD", testCutoff);
        assertNotNull(eurUsdSentiment);
        assertEquals("EUR_USD", eurUsdSentiment.asset());
        assertEquals(-0.1, eurUsdSentiment.sentimentScore(), 0.001); // (45.0 - 55.0) / 100.0 = -0.1
        assertTrue(eurUsdSentiment.retailRatioString().contains("45% Long"));
        assertTrue(eurUsdSentiment.retailRatioString().contains("55% Short"));

        // Query GBP_USD
        SentimentData gbpUsdSentiment = sentimentTools.fetchMarketSentiment("GBP/USD", testCutoff);
        assertNotNull(gbpUsdSentiment);
        assertEquals(0.4, gbpUsdSentiment.sentimentScore(), 0.001); // (70.0 - 30.0) / 100.0 = 0.4

        // Query at a cutoff BEFORE the brief was generated (should fall back to default since brief is in the future relative to cutoff)
        Instant earlyCutoff = testCutoff.minus(10, java.time.temporal.ChronoUnit.DAYS);
        SentimentData oldSentiment = sentimentTools.fetchMarketSentiment("EUR_USD", earlyCutoff);
        assertNotNull(oldSentiment);
        assertEquals(0.0, oldSentiment.sentimentScore());
        assertEquals("50% Long / 50% Short", oldSentiment.retailRatioString());
    }

    @Test
    void testSeasonalityToolsCalculation() {
        SeasonalityTools seasonalityTools = new SeasonalityTools();

        // 1. Cutoff in 2027: both 2025 and 2026 week 21 are visible (2027 week 21 starts May 24)
        Instant cutoff2027 = Instant.parse("2027-05-26T12:00:00Z");
        
        SeasonalityData seasonality2027 = seasonalityTools.fetchWeeklySeasonality("EUR_USD", cutoff2027);
        assertNotNull(seasonality2027);
        assertEquals("EUR_USD", seasonality2027.asset());
        assertEquals(21, seasonality2027.weekOfYear());
        assertEquals(MarketDirection.BULLISH, seasonality2027.directionalBias());
        // Average pips = (100 + 150) / 2 = 125
        assertEquals(125, seasonality2027.averagePips());

        // 2. Cutoff in 2026: only 2025 week 21 is visible (2026 week 21 belongs to cutoffYear and is excluded)
        Instant cutoff2026 = Instant.parse("2026-05-20T12:00:00Z");
        
        SeasonalityData seasonality2026 = seasonalityTools.fetchWeeklySeasonality("EUR_USD", cutoff2026);
        assertNotNull(seasonality2026);
        assertEquals(MarketDirection.BULLISH, seasonality2026.directionalBias());
        // Average pips = 100 (only 2025 is visible before the cutoff!)
        assertEquals(100, seasonality2026.averagePips());
    }
}
