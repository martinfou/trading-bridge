package com.martinfou.trading.backtest.report;

import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.MonteCarloSimulation;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Trade;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link HtmlReportGenerator}.
 */
class HtmlReportGeneratorTest {

    @TempDir
    Path tempDir;

    @Test
    void generate_basicResult_createsHtmlFile() {
        BacktestResult result = createSampleResult();
        HtmlReportGenerator gen = new HtmlReportGenerator(result);

        Path output = tempDir.resolve("report.html");
        Path actual = gen.generate(output);

        assertTrue(Files.exists(actual));
        assertTrue(actual.toString().endsWith("report.html"));
    }

    @Test
    void generate_htmlContainsKeyMetrics() throws IOException {
        BacktestResult result = createSampleResult();
        HtmlReportGenerator gen = new HtmlReportGenerator(result);

        Path output = tempDir.resolve("test-report.html");
        gen.generate(output);

        String html = Files.readString(output);
        assertTrue(html.contains("SMA Test"));
        assertTrue(html.contains("Overview"));
        assertTrue(html.contains("Equity Curve"));
        assertTrue(html.contains("chart.js"));
        assertTrue(html.contains("Total Return"));
        assertTrue(html.contains("Sharpe"));
        assertTrue(html.contains("Profit"));
        assertTrue(html.contains("Drawdown"));
    }

    @Test
    void generate_withMonteCarlo_includesMCTab() throws IOException {
        BacktestResult result = createSampleResult();
        // Build a Monte Carlo result with 10 runs
        List<Double> pnls = List.of(-100.0, -50.0, 0.0, 50.0, 100.0, 150.0, 200.0, 250.0, 300.0, 350.0);
        List<Double> dds = List.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0);
        List<Double> shs = List.of(-0.5, 0.0, 0.5, 1.0, 1.5, 2.0, 2.5, 3.0, 3.5, 4.0);
        MonteCarloSimulation.Result mcResult =
            new MonteCarloSimulation.Result(10, pnls, dds, shs, 10000.0);

        HtmlReportGenerator gen = new HtmlReportGenerator(result)
            .withMonteCarlo(mcResult);

        Path output = tempDir.resolve("mc-report.html");
        gen.generate(output);

        String html = Files.readString(output);
        assertTrue(html.contains("Monte Carlo"));
        assertTrue(html.contains("Median P&amp;L"));
    }

    @Test
    void generate_withComparison_includesComparisonTab() throws IOException {
        BacktestResult r1 = createSampleResult();
        BacktestResult r2 = createSampleResult();
        HtmlReportGenerator gen = new HtmlReportGenerator(r1)
            .withComparison("Extra", r2);

        Path output = tempDir.resolve("comp-report.html");
        gen.generate(output);

        String html = Files.readString(output);
        assertTrue(html.contains("Comparison"));
        assertTrue(html.contains("Extra"));
    }

    @Test
    void generate_noTrades_doesNotThrow() {
        BacktestResult result = BacktestResult.builder()
            .strategyName("Empty")
            .initialCapital(10000)
            .finalEquity(10000)
            .equityCurve(List.of(10000.0))
            .trades(List.of())
            .periodStart(Instant.parse("2026-01-01T00:00:00Z"))
            .periodEnd(Instant.parse("2026-06-01T00:00:00Z"))
            .build();

        HtmlReportGenerator gen = new HtmlReportGenerator(result);
        assertDoesNotThrow(() -> gen.generate(tempDir.resolve("empty.html")));
    }

    // ---------------------------------------------------------------
    //  Helper
    // ---------------------------------------------------------------

    private static BacktestResult createSampleResult() {
        List<Trade> trades = List.of(
            new Trade("EURUSD", Order.Side.BUY, 1.1000, 1.1050, 10000,
                Instant.parse("2026-01-05T00:00:00Z"), Instant.parse("2026-01-10T00:00:00Z")),
            new Trade("EURUSD", Order.Side.SELL, 1.1050, 1.1020, 10000,
                Instant.parse("2026-01-15T00:00:00Z"), Instant.parse("2026-01-20T00:00:00Z")),
            new Trade("GBPUSD", Order.Side.BUY, 1.2500, 1.2600, 5000,
                Instant.parse("2026-02-01T00:00:00Z"), Instant.parse("2026-02-05T00:00:00Z"))
        );

        return BacktestResult.builder()
            .strategyName("SMA Test")
            .initialCapital(10000.0)
            .finalEquity(10575.0)
            .totalPnl(575.0)
            .totalReturnPct(5.75)
            .totalTrades(3)
            .winningTrades(2)
            .losingTrades(1)
            .winRatePct(66.67)
            .maxDrawdownPct(3.2)
            .avgTradePnl(191.67)
            .sharpeRatio(1.45)
            .sortinoRatio(2.1)
            .profitFactor(2.8)
            .calmarRatio(1.8)
            .totalCommission(15.0)
            .totalSlippage(5.0)
            .equityCurve(List.of(10000.0, 10050.0, 10020.0, 10100.0, 10080.0, 10200.0, 10575.0))
            .trades(trades)
            .periodStart(Instant.parse("2026-01-01T00:00:00Z"))
            .periodEnd(Instant.parse("2026-06-01T00:00:00Z"))
            .build();
    }
}
