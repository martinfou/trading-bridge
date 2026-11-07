package com.martinfou.trading.data;

import com.martinfou.trading.core.Bar;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HistoricalDataLoadersTest {

    @Test
    void testYahooFinanceCsvLoading() throws IOException {
        String csvContent = """
            Date,Open,High,Low,Close,Adj Close,Volume
            2024-01-02,4980.25,5020.50,4975.00,5010.00,5010.00,154200
            2024-01-03,5012.00,5035.75,5005.25,5025.50,5025.50,162100
            """;

        List<Bar> bars = YahooFinanceDataLoader.loadCsv(
            new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8)),
            "MES"
        );

        assertEquals(2, bars.size());
        Bar b0 = bars.get(0);
        assertEquals("MES", b0.symbol());
        assertEquals(Instant.parse("2024-01-02T00:00:00Z"), b0.timestamp());
        assertEquals(4980.25, b0.open(), 1e-6);
        assertEquals(5020.50, b0.high(), 1e-6);
        assertEquals(4975.00, b0.low(), 1e-6);
        assertEquals(5010.00, b0.close(), 1e-6);
        assertEquals(154200L, b0.volume());

        // Symbol mapping
        assertEquals("MES", YahooFinanceDataLoader.mapProxySymbol("ES=F"));
        assertEquals("M2K", YahooFinanceDataLoader.mapProxySymbol("RTY=F"));
        assertEquals("MNQ", YahooFinanceDataLoader.mapProxySymbol("NQ=F"));
        assertEquals("IWM", YahooFinanceDataLoader.mapProxySymbol("IWM"));

        assertEquals("ES=F", YahooFinanceDataLoader.toYahooTicker("MES"));
        assertEquals("NQ=F", YahooFinanceDataLoader.toYahooTicker("MNQ"));
        assertEquals("RTY=F", YahooFinanceDataLoader.toYahooTicker("M2K"));
        assertEquals("GC=F", YahooFinanceDataLoader.toYahooTicker("MGC"));
        assertEquals("EURUSD=X", YahooFinanceDataLoader.toYahooTicker("EUR_USD"));
    }

    @Test
    void testYahooFinanceLookbackRulesAndChartJsonParsing() throws IOException {
        LocalDate today = LocalDate.of(2026, 11, 6);
        assertTrue(YahooFinanceDataLoader.canServeYear("M1", 2026, today));
        assertFalse(YahooFinanceDataLoader.canServeYear("M1", 2025, today));
        assertFalse(YahooFinanceDataLoader.canServeFullYear("M1", 2026, today));

        assertTrue(YahooFinanceDataLoader.canServeYear("M30", 2026, today));
        assertFalse(YahooFinanceDataLoader.canServeYear("M30", 2025, today));
        assertFalse(YahooFinanceDataLoader.canServeFullYear("M30", 2026, today));

        assertTrue(YahooFinanceDataLoader.canServeYear("H1", 2025, today));
        assertTrue(YahooFinanceDataLoader.canServeFullYear("H1", 2025, today));
        assertFalse(YahooFinanceDataLoader.canServeYear("H1", 2020, today));

        assertTrue(YahooFinanceDataLoader.canServeFullYear("D1", 2010, today));

        String sampleChartJson = """
            {
              "chart": {
                "result": [
                  {
                    "timestamp": [1730467800, 1730469600],
                    "indicators": {
                      "quote": [
                        {
                          "open": [2750.10, 2752.40],
                          "high": [2755.00, 2758.20],
                          "low": [2748.50, 2751.00],
                          "close": [2752.40, 2756.80],
                          "volume": [1240, 980]
                        }
                      ]
                    }
                  }
                ],
                "error": null
              }
            }
            """;

        Clock fixedClock = Clock.fixed(Instant.parse("2024-11-15T12:00:00Z"), ZoneOffset.UTC);
        List<String> requestedIntervals = new ArrayList<>();
        List<Bar> m30Bars = YahooFinanceDataLoader.fetchRecentBars(
            "MGC",
            "M30",
            10,
            fixedClock,
            (ticker, p1, p2, interval) -> {
                assertEquals("GC=F", ticker);
                requestedIntervals.add(interval);
                return sampleChartJson;
            }
        );

        assertEquals(List.of("30m"), requestedIntervals);
        assertEquals(2, m30Bars.size());
        assertEquals("MGC", m30Bars.get(0).symbol());
        assertEquals(2750.10, m30Bars.get(0).open(), 1e-6);
        assertEquals(2756.80, m30Bars.get(1).close(), 1e-6);

        // Verify M1 automatically chunks requests > 7 days into 7-day sub-requests
        List<Long> chunkStarts = new ArrayList<>();
        YahooFinanceDataLoader.fetchRecentBars(
            "MES",
            "M1",
            20,
            fixedClock,
            (ticker, p1, p2, interval) -> {
                assertEquals("ES=F", ticker);
                assertEquals("1m", interval);
                assertTrue((p2 - p1) <= 7L * 86400L);
                chunkStarts.add(p1);
                return sampleChartJson;
            }
        );
        assertEquals(3, chunkStarts.size(), "20 days of M1 must be split into 3 <=7-day chunks");
    }

    @Test
    void testIbkrCsvLoading() throws IOException {
        String ibkrCsv = """
            Date,Open,High,Low,Close,Volume,Average,BarCount
            20240102  09:30:00,4980.0,4995.0,4978.0,4990.0,1250,4986.5,350
            20240102  10:30:00,4990.0,5010.0,4985.0,5005.0,2100,4998.0,480
            """;

        List<Bar> bars = IbkrHistoricalDataLoader.loadCsv(
            new ByteArrayInputStream(ibkrCsv.getBytes(StandardCharsets.UTF_8)),
            "MES"
        );

        assertEquals(2, bars.size());
        Bar b0 = bars.get(0);
        assertEquals("MES", b0.symbol());
        // 09:30:00 EST (America/New_York) is 14:30:00 UTC
        assertEquals(Instant.parse("2024-01-02T14:30:00Z"), b0.timestamp());
        assertEquals(4980.0, b0.open(), 1e-6);
        assertEquals(5005.0, bars.get(1).close(), 1e-6);
    }
}

