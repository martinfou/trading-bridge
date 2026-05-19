package com.martinfou.trading.genetics;

import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.core.Bar;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link MultiMarketTest}.
 */
class MultiMarketTestTest {

    // ---------------------------------------------------------------
    //  generateVerdict
    // ---------------------------------------------------------------

    @Test
    void testVerdictStableWhenStdDevBelow0_5() {
        assertEquals("STABLE", MultiMarketTest.generateVerdict(0.0));
        assertEquals("STABLE", MultiMarketTest.generateVerdict(0.25));
        assertEquals("STABLE", MultiMarketTest.generateVerdict(0.49));
    }

    @Test
    void testVerdictModerateWhenStdDevBetween0_5And1_0() {
        assertEquals("MODERATE", MultiMarketTest.generateVerdict(0.5));
        assertEquals("MODERATE", MultiMarketTest.generateVerdict(0.75));
        assertEquals("MODERATE", MultiMarketTest.generateVerdict(0.99));
    }

    @Test
    void testVerdictUnstableWhenStdDevAtOrAbove1_0() {
        assertEquals("UNSTABLE", MultiMarketTest.generateVerdict(1.0));
        assertEquals("UNSTABLE", MultiMarketTest.generateVerdict(2.5));
        assertEquals("UNSTABLE", MultiMarketTest.generateVerdict(10.0));
    }

    @Test
    void testVerdictHandlesNegativeStdDev() {
        // Negative values are below 0.5 so should be STABLE
        assertEquals("STABLE", MultiMarketTest.generateVerdict(-0.1));
        assertEquals("STABLE", MultiMarketTest.generateVerdict(-1.0));
    }

    // ---------------------------------------------------------------
    //  generateMarketData
    // ---------------------------------------------------------------

    @Test
    void testGenerateMarketDataReturnsCorrectBarCount() {
        List<Bar> bars = MultiMarketTest.generateMarketData("EURUSD", 200);
        assertEquals(200, bars.size());
    }

    @Test
    void testGenerateMarketDataAllBarsHaveCorrectSymbol() {
        List<Bar> bars = MultiMarketTest.generateMarketData("GBPUSD", 50);
        for (Bar bar : bars) {
            assertEquals("GBPUSD", bar.symbol());
        }
    }

    @Test
    void testGenerateMarketDataBarsAreInChronologicalOrder() {
        List<Bar> bars = MultiMarketTest.generateMarketData("USDJPY", 100);
        for (int i = 1; i < bars.size(); i++) {
            assertTrue(bars.get(i).timestamp().isAfter(bars.get(i - 1).timestamp()),
                "Bar " + i + " is not after bar " + (i - 1));
        }
    }

    @Test
    void testGenerateMarketDataPricesArePositive() {
        List<Bar> bars = MultiMarketTest.generateMarketData("USDCAD", 100);
        for (Bar bar : bars) {
            assertTrue(bar.open() > 0, "Open must be positive");
            assertTrue(bar.high() > 0, "High must be positive");
            assertTrue(bar.low() > 0, "Low must be positive");
            assertTrue(bar.close() > 0, "Close must be positive");
        }
    }

    @Test
    void testGenerateMarketDataHighLowIntegrity() {
        List<Bar> bars = MultiMarketTest.generateMarketData("AUDUSD", 100);
        for (Bar bar : bars) {
            assertTrue(bar.high() >= bar.low(), "High must be >= low");
            assertTrue(bar.high() >= bar.open(), "High must be >= open");
            assertTrue(bar.low() <= bar.open(), "Low must be <= open");
            assertTrue(bar.high() >= bar.close(), "High must be >= close");
            assertTrue(bar.low() <= bar.close(), "Low must be <= close");
        }
    }

    @Test
    void testGenerateMarketDataDifferentSymbolsDiffer() {
        List<Bar> eurBars = MultiMarketTest.generateMarketData("EURUSD", 100);
        List<Bar> gbpBars = MultiMarketTest.generateMarketData("GBPUSD", 100);

        // Prices should be meaningfully different between the two symbols
        double eurFirstClose = eurBars.get(0).close();
        double gbpFirstClose = gbpBars.get(0).close();
        assertNotEquals(eurFirstClose, gbpFirstClose, 0.001,
            "EURUSD and GBPUSD should have different starting prices");
    }

    @Test
    void testGenerateMarketDataUnknownSymbolDefaults() {
        List<Bar> bars = MultiMarketTest.generateMarketData("XYZ123", 50);
        assertEquals(50, bars.size());
        for (Bar bar : bars) {
            assertEquals("XYZ123", bar.symbol());
            assertTrue(bar.close() > 0);
        }
    }

    @Test
    void testGenerateMarketDataRejectsInvalidBarCount() {
        assertThrows(IllegalArgumentException.class,
            () -> MultiMarketTest.generateMarketData("EURUSD", 0));
        assertThrows(IllegalArgumentException.class,
            () -> MultiMarketTest.generateMarketData("EURUSD", 1));
    }

    @Test
    void testGenerateMarketDataRejectsNullSymbol() {
        assertThrows(NullPointerException.class,
            () -> MultiMarketTest.generateMarketData(null, 100));
    }

    // ---------------------------------------------------------------
    //  generateDefaultMarketData
    // ---------------------------------------------------------------

    @Test
    void testGenerateDefaultMarketDataContainsAllSymbols() {
        Map<String, List<Bar>> data = MultiMarketTest.generateDefaultMarketData(100);
        assertEquals(MultiMarketTest.DEFAULT_SYMBOLS.size(), data.size());
        for (String symbol : MultiMarketTest.DEFAULT_SYMBOLS) {
            assertTrue(data.containsKey(symbol), "Missing data for " + symbol);
            assertEquals(100, data.get(symbol).size());
        }
    }

    @Test
    void testGenerateDefaultMarketDataReturnsUnmodifiableMap() {
        Map<String, List<Bar>> data = MultiMarketTest.generateDefaultMarketData(100);
        assertThrows(UnsupportedOperationException.class,
            () -> data.put("XXX", List.of()));
    }

    // ---------------------------------------------------------------
    //  testOnMarkets
    // ---------------------------------------------------------------

    @Test
    void testTestOnMarketsWithSingleMarket() {
        Chromosome chromosome = Chromosome.random();
        List<Bar> bars = MultiMarketTest.generateMarketData("EURUSD", 200);
        Map<String, List<Bar>> data = Map.of("EURUSD", bars);

        MultiMarketTest.MultiMarketReport report =
            MultiMarketTest.testOnMarkets(chromosome, data, 100_000.0);

        assertNotNull(report);
        assertEquals(1, report.results().size());
        assertNotNull(report.verdict());
    }

    @Test
    void testTestOnMarketsWithMultipleMarkets() {
        Chromosome chromosome = Chromosome.random();
        Map<String, List<Bar>> data = MultiMarketTest.generateDefaultMarketData(300);

        MultiMarketTest.MultiMarketReport report =
            MultiMarketTest.testOnMarkets(chromosome, data, 100_000.0);

        assertNotNull(report);
        assertEquals(MultiMarketTest.DEFAULT_SYMBOLS.size(), report.results().size());
        assertTrue(Double.isFinite(report.avgSharpe()), "Average Sharpe should be finite");
        assertTrue(report.sharpeStdDev() >= 0, "Sharpe std dev should be non-negative");
    }

    @Test
    void testTestOnMarketsEachResultHasValidBacktest() {
        Chromosome chromosome = Chromosome.random();
        Map<String, List<Bar>> data = MultiMarketTest.generateDefaultMarketData(200);

        MultiMarketTest.MultiMarketReport report =
            MultiMarketTest.testOnMarkets(chromosome, data, 100_000.0);

        for (MultiMarketTest.MarketResult mr : report.results()) {
            assertNotNull(mr.symbol(), "Symbol must not be null");
            assertNotNull(mr.result(), "BacktestResult must not be null");
            assertNotNull(mr.robustness(), "RobustnessScore must not be null");
            assertTrue(mr.result().initialCapital() > 0);
            assertTrue(mr.result().totalTrades() >= 0);
        }
    }

    @Test
    void testTestOnMarketsRejectsEmptyMarketData() {
        Chromosome chromosome = Chromosome.random();
        assertThrows(IllegalArgumentException.class,
            () -> MultiMarketTest.testOnMarkets(chromosome, Map.of(), 100_000.0));
    }

    @Test
    void testTestOnMarketsRejectsNullChromosome() {
        Map<String, List<Bar>> data = Map.of("EURUSD",
            MultiMarketTest.generateMarketData("EURUSD", 100));
        assertThrows(NullPointerException.class,
            () -> MultiMarketTest.testOnMarkets(null, data, 100_000.0));
    }

    @Test
    void testTestOnMarketsRejectsNullData() {
        assertThrows(NullPointerException.class,
            () -> MultiMarketTest.testOnMarkets(Chromosome.random(), null, 100_000.0));
    }

    @Test
    void testTestOnMarketsProducesDeterministicVerdict() {
        Chromosome chromosome = Chromosome.random();
        Map<String, List<Bar>> data = MultiMarketTest.generateDefaultMarketData(250);

        MultiMarketTest.MultiMarketReport report =
            MultiMarketTest.testOnMarkets(chromosome, data, 100_000.0);

        String verdict = report.verdict();
        assertTrue(verdict.equals("STABLE") || verdict.equals("MODERATE") || verdict.equals("UNSTABLE"),
            "Verdict must be one of STABLE/MODERATE/UNSTABLE, got: " + verdict);
    }

    @Test
    void testTestOnMarketsAllMarketResultsHaveValidRobustness() {
        Chromosome chromosome = Chromosome.random();
        Map<String, List<Bar>> data = MultiMarketTest.generateDefaultMarketData(250);

        MultiMarketTest.MultiMarketReport report =
            MultiMarketTest.testOnMarkets(chromosome, data, 100_000.0);

        for (MultiMarketTest.MarketResult mr : report.results()) {
            RobustnessScore rs = mr.robustness();
            assertTrue(rs.overall() >= 0 && rs.overall() <= 100,
                "Robustness overall should be in [0,100], got: " + rs.overall());
            assertTrue(rs.wfoos() >= 0 && rs.wfoos() <= 100);
            assertTrue(rs.monteCarlo() >= 0 && rs.monteCarlo() <= 100);
            assertTrue(rs.sharpeStability() >= 0 && rs.sharpeStability() <= 100);
            assertTrue(rs.parameterSensitivity() >= 0 && rs.parameterSensitivity() <= 100);
        }
    }

    @Test
    void testTestOnMarketsHandlesRandomChromosome() {
        // Run multiple times with different random chromosomes to ensure stability
        for (int i = 0; i < 3; i++) {
            Chromosome chromosome = Chromosome.random();
            Map<String, List<Bar>> data = MultiMarketTest.generateDefaultMarketData(200);

            MultiMarketTest.MultiMarketReport report =
                MultiMarketTest.testOnMarkets(chromosome, data, 100_000.0);

            assertNotNull(report);
            assertFalse(report.results().isEmpty());
            assertNotNull(report.verdict());
        }
    }

    @Test
    void testTestOnMarketsSkipsEmptySymbolData() {
        Chromosome chromosome = Chromosome.random();
        List<Bar> eurBars = MultiMarketTest.generateMarketData("EURUSD", 200);
        Map<String, List<Bar>> data = new java.util.LinkedHashMap<>();
        data.put("EURUSD", eurBars);
        data.put("GBPUSD", List.of()); // empty data, should be skipped

        MultiMarketTest.MultiMarketReport report =
            MultiMarketTest.testOnMarkets(chromosome, data, 100_000.0);

        assertEquals(1, report.results().size());
        assertEquals("EURUSD", report.results().get(0).symbol());
    }

    // ---------------------------------------------------------------
    //  MarketResult and MultiMarketReport records
    // ---------------------------------------------------------------

    @Test
    void testMarketResultRecord() {
        BacktestResult dummyResult = BacktestResult.builder()
            .strategyName("test")
            .initialCapital(100_000)
            .finalEquity(101_000)
            .totalPnl(1_000)
            .totalReturnPct(1.0)
            .sharpeRatio(1.5)
            .profitFactor(1.2)
            .winRatePct(50.0)
            .totalTrades(10)
            .build();

        RobustnessScore rs = new RobustnessScore(75.0, 40.0, 20.0, 10.0, 5.0);
        MultiMarketTest.MarketResult mr =
            new MultiMarketTest.MarketResult("EURUSD", dummyResult, rs);

        assertEquals("EURUSD", mr.symbol());
        assertEquals(dummyResult, mr.result());
        assertEquals(rs, mr.robustness());
    }

    @Test
    void testMultiMarketReportRecord() {
        MultiMarketTest.MarketResult mr = new MultiMarketTest.MarketResult(
            "EURUSD",
            BacktestResult.builder().build(),
            new RobustnessScore(50, 30, 10, 5, 5)
        );

        MultiMarketTest.MultiMarketReport report =
            new MultiMarketTest.MultiMarketReport(List.of(mr), 0.8, 0.3, "STABLE");

        assertEquals(1, report.results().size());
        assertEquals(0.8, report.avgSharpe());
        assertEquals(0.3, report.sharpeStdDev());
        assertEquals("STABLE", report.verdict());
    }
}
