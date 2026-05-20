package com.martinfou.trading.backtest.report;

import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Trade;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link BacktestJsonExporter}.
 */
class BacktestJsonExporterTest {

    private static final double INITIAL_CAPITAL = 10000.0;

    @Test
    void toJson_returnsValidJsonStructure() {
        BacktestResult result = makeTestResult(50, 10);
        String json = new BacktestJsonExporter(result).toJson();

        assertNotNull(json);
        assertTrue(json.startsWith("{"));
        assertTrue(json.endsWith("}"));

        assertTrue(json.contains("\"strategyName\""));
        assertTrue(json.contains("\"period\""));
        assertTrue(json.contains("\"metrics\""));
        assertTrue(json.contains("\"equityCurve\""));
        assertTrue(json.contains("\"trades\""));
    }

    @Test
    void toJson_metricsContainAllExpectedFields() {
        BacktestResult result = makeTestResult(100, 20);
        String json = new BacktestJsonExporter(result).toJson();

        assertTrue(json.contains("\"totalTrades\""));
        assertTrue(json.contains("\"winRate\""));
        assertTrue(json.contains("\"totalReturn\""));
        assertTrue(json.contains("\"initialBalance\""));
        assertTrue(json.contains("\"finalBalance\""));
        assertTrue(json.contains("\"maxDrawdown\""));
        assertTrue(json.contains("\"profitFactor\""));
        assertTrue(json.contains("\"sharpeRatio\""));
        assertTrue(json.contains("\"avgWin\""));
        assertTrue(json.contains("\"avgLoss\""));
        assertTrue(json.contains("\"maxConsecutiveWins\""));
        assertTrue(json.contains("\"maxConsecutiveLosses\""));
        assertTrue(json.contains("\"sortinoRatio\""));
        assertTrue(json.contains("\"calmarRatio\""));
        assertTrue(json.contains("\"totalCommission\""));
        assertTrue(json.contains("\"totalSlippage\""));
    }

    @Test
    void toJson_equityCurvePresent() {
        BacktestResult result = makeTestResult(50, 5);
        String json = new BacktestJsonExporter(result).toJson();

        assertTrue(json.contains("\"equityCurve\""), "JSON should contain equityCurve field");
        // Should have numeric values, not just empty brackets
        assertTrue(json.contains("10000."), "JSON should contain initial equity value");
    }

    @Test
    void toJson_tradesStructure() {
        BacktestResult result = makeTestResult(200, 15);
        String json = new BacktestJsonExporter(result).toJson();

        assertTrue(json.contains("\"entryTime\""));
        assertTrue(json.contains("\"exitTime\""));
        assertTrue(json.contains("\"entryPrice\""));
        assertTrue(json.contains("\"exitPrice\""));
        assertTrue(json.contains("\"direction\""));
        assertTrue(json.contains("\"pnlDollars\""));
        assertTrue(json.contains("\"pnlPips\""));
        assertTrue(json.contains("\"barsHeld\""));
    }

    @Test
    void toJson_parsesAsValidJson() {
        BacktestResult result = makeTestResult(100, 12);
        String json = new BacktestJsonExporter(result).toJson();

        int openBraces = countChar(json, '{');
        int closeBraces = countChar(json, '}');
        assertEquals(openBraces, closeBraces, "Unbalanced JSON braces");

        int openBrackets = countChar(json, '[');
        int closeBrackets = countChar(json, ']');
        assertEquals(openBrackets, closeBrackets, "Unbalanced JSON brackets");
    }

    @Test
    void writeJson_createsFile() throws IOException {
        BacktestResult result = makeTestResult(100, 10);
        Path tempDir = Files.createTempDirectory("backtest-json-test");
        Path output = tempDir.resolve("test_report.json");

        BacktestJsonExporter exporter = new BacktestJsonExporter(result);
        Path written = exporter.writeJson(output);

        assertTrue(Files.exists(written));
        assertTrue(Files.size(written) > 100);

        String content = Files.readString(written);
        assertTrue(content.contains("TestStrategy"));

        Files.deleteIfExists(written);
        Files.deleteIfExists(tempDir);
    }

    @Test
    void toJson_emptyTrades_returnsValidJson() {
        BacktestResult empty = BacktestResult.builder()
            .strategyName("EmptyTest")
            .initialCapital(10000)
            .finalEquity(10000)
            .totalPnl(0)
            .totalReturnPct(0)
            .totalTrades(0)
            .winningTrades(0)
            .losingTrades(0)
            .winRatePct(0)
            .maxDrawdownPct(0)
            .avgTradePnl(0)
            .sharpeRatio(0)
            .sortinoRatio(0)
            .profitFactor(0)
            .calmarRatio(0)
            .totalCommission(0)
            .totalSlippage(0)
            .equityCurve(List.of(10000.0, 10000.0, 10000.0))
            .trades(List.of())
            .periodStart(Instant.parse("2024-01-01T00:00:00Z"))
            .periodEnd(Instant.parse("2024-06-01T00:00:00Z"))
            .build();

        String json = new BacktestJsonExporter(empty).toJson();
        assertTrue(json.contains("\"trades\": []"));
    }

    @Test
    void toJson_profitFactorAndSharpeFormattedCorrectly() {
        BacktestResult result = BacktestResult.builder()
            .strategyName("MetricTest")
            .initialCapital(10000)
            .finalEquity(16956.35)
            .totalPnl(6956.35)
            .totalReturnPct(69.56)
            .totalTrades(45)
            .winningTrades(25)
            .losingTrades(20)
            .winRatePct(55.6)
            .maxDrawdownPct(25.94)
            .avgTradePnl(154.59)
            .sharpeRatio(1.48)
            .sortinoRatio(1.12)
            .profitFactor(1.384)
            .calmarRatio(2.68)
            .totalCommission(45.0)
            .totalSlippage(22.5)
            .equityCurve(List.of(10000.0, 12000.0, 11000.0, 16956.35))
            .trades(List.of())
            .periodStart(Instant.parse("2024-01-01T00:00:00Z"))
            .periodEnd(Instant.parse("2024-12-31T00:00:00Z"))
            .build();

        String json = new BacktestJsonExporter(result).toJson();
        assertTrue(json.contains("\"profitFactor\": 1.384"));
        assertTrue(json.contains("\"sharpeRatio\": 1.48"));
        assertTrue(json.contains("\"totalReturn\": 69.56"));
    }

    @Test
    void toJson_tradesWithConsecutiveWinLossTracking() {
        Instant now = Instant.parse("2024-01-01T00:00:00Z");
        List<Trade> trades = List.of(
            new Trade("EURUSD", Order.Side.BUY, 1.08000, 1.09000, 1000, now, now.plusSeconds(3600)),
            new Trade("EURUSD", Order.Side.BUY, 1.09000, 1.10000, 1000, now.plusSeconds(7200), now.plusSeconds(10800)),
            new Trade("EURUSD", Order.Side.BUY, 1.10000, 1.09000, 1000, now.plusSeconds(14400), now.plusSeconds(18000)),
            new Trade("EURUSD", Order.Side.BUY, 1.09000, 1.09500, 1000, now.plusSeconds(21600), now.plusSeconds(25200))
        );

        BacktestResult result = BacktestResult.builder()
            .strategyName("ConsecutiveTest")
            .initialCapital(10000)
            .finalEquity(10500)
            .totalPnl(500)
            .totalReturnPct(5.0)
            .totalTrades(4)
            .winningTrades(3)
            .losingTrades(1)
            .winRatePct(75.0)
            .maxDrawdownPct(5.0)
            .avgTradePnl(125)
            .sharpeRatio(1.5)
            .sortinoRatio(1.0)
            .profitFactor(3.0)
            .calmarRatio(1.0)
            .totalCommission(0)
            .totalSlippage(0)
            .equityCurve(List.of(10000.0, 10100.0, 10200.0, 10150.0, 10500.0))
            .trades(trades)
            .periodStart(now)
            .periodEnd(now.plusSeconds(30000))
            .build();

        String json = new BacktestJsonExporter(result).toJson();
        assertTrue(json.contains("\"maxConsecutiveWins\": 2"));
        assertTrue(json.contains("\"maxConsecutiveLosses\": 1"));
    }

    @Test
    void toJson_jsonEscapesStrings() {
        BacktestResult result = BacktestResult.builder()
            .strategyName("Test \"Quote\" Strategy")
            .initialCapital(10000)
            .finalEquity(10000)
            .totalPnl(0)
            .totalReturnPct(0)
            .totalTrades(0)
            .winningTrades(0)
            .losingTrades(0)
            .winRatePct(0)
            .maxDrawdownPct(0)
            .avgTradePnl(0)
            .sharpeRatio(0)
            .sortinoRatio(0)
            .profitFactor(0)
            .calmarRatio(0)
            .totalCommission(0)
            .totalSlippage(0)
            .equityCurve(List.of(10000.0))
            .trades(List.of())
            .periodStart(Instant.parse("2024-01-01T00:00:00Z"))
            .periodEnd(Instant.parse("2024-06-01T00:00:00Z"))
            .build();

        String json = new BacktestJsonExporter(result).toJson();
        assertTrue(json.contains("\\\"Quote\\\""));
    }

    // ---------------------------------------------------------------
    //  Helpers
    // ---------------------------------------------------------------

    private static BacktestResult makeTestResult(int equityCurvePoints, int tradeCount) {
        Instant start = Instant.parse("2024-01-01T00:00:00Z");

        // Build equity curve with a mild uptrend
        List<Double> equityCurve = new java.util.ArrayList<>();
        for (int i = 0; i < equityCurvePoints; i++) {
            equityCurve.add(INITIAL_CAPITAL + (double) i / equityCurvePoints * 2000
                + Math.sin(i * 0.3) * 500);
        }

        // Build some trades
        Instant now = start;
        List<Trade> trades = new java.util.ArrayList<>();
        for (int i = 0; i < tradeCount; i++) {
            double pnl = (i % 3 == 0) ? -50.0 : 100.0; // 2/3 winners
            trades.add(new Trade(
                "EURUSD",
                Order.Side.BUY,
                1.08000 + i * 0.001,
                1.08000 + i * 0.001 + pnl / 10000.0,
                1000.0,
                now,
                now.plusSeconds(3600)
            ));
            now = now.plusSeconds(7200);
        }

        double finalEquity = INITIAL_CAPITAL + tradeCount * 50; // ~net positive
        double totalReturn = (finalEquity - INITIAL_CAPITAL) / INITIAL_CAPITAL * 100;

        int winners = (int) trades.stream().filter(t -> t.pnl() > 0).count();
        int losers = tradeCount - winners;
        double winRate = tradeCount > 0 ? (double) winners / tradeCount * 100 : 0;
        double maxDd = 10.0; // simplified
        double avgPnl = tradeCount > 0 ? (finalEquity - INITIAL_CAPITAL) / tradeCount : 0;

        return new BacktestResult(
            "TestStrategy",
            INITIAL_CAPITAL, finalEquity,
            finalEquity - INITIAL_CAPITAL, totalReturn,
            tradeCount, winners, losers, winRate, maxDd, avgPnl,
            1.5, 1.2, 2.5, 0.8,
            0.0, 0.0,
            equityCurve, trades,
            start, now
        );
    }

    private static int countChar(String s, char c) {
        return (int) s.chars().filter(ch -> ch == c).count();
    }
}
